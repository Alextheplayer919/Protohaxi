package dev.sora.protohax.relay.modules.motion

import dev.sora.protohax.relay.modules.util.ModuleCombat
import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventPacketOutbound
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LeHu-style motion fly (port of the Rubidium `MotionFly` / Testfly design), replacing
 * the ability-based fly modules (`ModuleAbilityFly` in this app and the bundled
 * library's `Fly`).
 *
 * Why the old fly is patched, even on anarchy servers: both of them work by telling
 * the *game* it has the fly ability (`UpdateAbilitiesPacket` with `FLYING`/`MAY_FLY`,
 * see [ModuleAbilityBase] and the library's `ModuleFly.abilityPacket`). The server
 * never grants that ability, so the movement that follows - sustained flight from a
 * survival player with no ability change on the wire - is exactly the signature
 * movement checks look for, and the usual answer is a setback/rubberband.
 *
 * This module never touches abilities at all:
 *
 *  * it reads the controls out of the outgoing [PlayerAuthInputPacket] - the analog
 *    move vector (`packet.motion`), the camera yaw (`packet.rotation.y`) and the
 *    up/down inputs (`WANT_UP`/`JUMPING`/`ASCEND`, `WANT_DOWN`/`SNEAKING`/`DESCEND`);
 *  * it turns them into a velocity, in blocks-per-second divided by 20 (per tick),
 *    with the horizontal speed capped at `sqrt(5.99)` blocks per tick - the same
 *    ceiling the LeHu original uses;
 *  * and it hands that velocity to the game with a client-bound
 *    `SetEntityMotionPacket` ([ModuleCombat.motion], the counterpart of Rubidium's
 *    `session.clientBound(...)`).
 *
 * The server is never asked for flight and never sent a fly flag: it only sees the
 * `PlayerAuthInputPacket` positions a client produces after being given motion,
 * which is the one channel a Bedrock proxy can still drive without permission.
 *
 * Two guards from the original are kept, adapted to what a relay can see:
 *
 *  * **Anti-rubberband** - if the reported position jumped more than
 *    [RUBBERBAND_DISTANCE] blocks since the last input packet (a setback, a teleport,
 *    a respawn), this tick is skipped and the new position becomes the reference, so
 *    the module never fights the server's correction with a fresh burst of motion;
 *  * **Collision consistency** - the relay has no block world (Rubidium's
 *    `CollisionGuard` reads one), but the client reports its own collisions in the
 *    same packet: while `HORIZONTAL_COLLISION` is set the horizontal request is
 *    dropped (hover instead of slamming a wall every tick), and a downward request
 *    is settled while `VERTICAL_COLLISION` is set (no pushing into the floor).
 *
 * Hold jump to climb and sneak to descend; with neither held the player glides at
 * [glideValue] instead of falling.
 */
class ModuleMotionFly : CheatModule("MotionFly", CheatCategory.MOVEMENT) {

	private var hSpeedBPS by floatValue("HSpeedBPS", 46.0f, 1.0f..100.0f)
	private var upSpeedBPS by floatValue("UpSpeedBPS", 19.8f, 1.0f..60.0f)
	private var downSpeedBPS by floatValue("DownSpeedBPS", 46.0f, 1.0f..60.0f)
	private var glideValue by floatValue("Glide", -0.02f, -0.3f..0.0f)
	private var upHFactor by floatValue("UpHFactor", 0.55f, 0.1f..1.0f)
	private var downHFactor by floatValue("DownHFactor", 0.55f, 0.1f..1.0f)
	private var antiRubberband by boolValue("AntiRubberband", true)
	private var collisionGuard by boolValue("CollisionGuard", true)

	/** Position reported by the previous input packet; the rubberband reference. */
	private var lastPos: Vector3f? = null

	override fun onEnable() {
		super.onEnable()
		lastPos = null
	}

	override fun onDisable() {
		super.onDisable()
		lastPos = null
		// Stop dead instead of keeping the last fly velocity: gravity takes over
		// from the next tick, which is the landing a player expects on toggle-off.
		runCatching { ModuleCombat.motion(session, 0f, 0f, 0f) }
	}

	private val handlePacketOutbound = handle<EventPacketOutbound> {
		val pk = packet as? PlayerAuthInputPacket ?: return@handle

		val selfPos = pk.position

		// Anti-rubberband: a position jump this large is the server correcting us
		// (or a teleport/respawn). Re-seed the reference and sit this tick out
		// rather than answering a setback with more motion.
		val last = lastPos
		if (antiRubberband && last != null) {
			val dx = selfPos.x - last.x
			val dy = selfPos.y - last.y
			val dz = selfPos.z - last.z
			if (sqrt((dx * dx + dy * dy + dz * dz).toDouble()) > RUBBERBAND_DISTANCE) {
				lastPos = selfPos
				return@handle
			}
		}

		val inputData = pk.inputData
		val wantUp = inputData.contains(PlayerAuthInputData.WANT_UP) ||
			inputData.contains(PlayerAuthInputData.JUMPING) ||
			inputData.contains(PlayerAuthInputData.ASCEND)
		val wantDown = inputData.contains(PlayerAuthInputData.WANT_DOWN) ||
			inputData.contains(PlayerAuthInputData.SNEAKING) ||
			inputData.contains(PlayerAuthInputData.DESCEND)

		// No vertical input, no move input and no glide: nothing to fly with.
		val analog = pk.motion
		if (!wantUp && !wantDown && analog.x == 0f && analog.y == 0f && glideValue == 0f) {
			lastPos = selfPos
			return@handle
		}

		// Horizontal speed, per tick, capped the way the original caps it.
		var horizSpeed = min(hSpeedBPS / 20f, MAX_HORIZONTAL_PER_TICK)
		if (wantUp) horizSpeed *= upHFactor
		if (wantDown) horizSpeed *= downHFactor

		// Vertical speed: climb, descend (plus the glide sink), or just glide.
		var vertSpeed = when {
			wantUp -> upSpeedBPS / 20f
			wantDown -> -downSpeedBPS / 20f + glideValue
			else -> glideValue
		}

		// Input rotation: the analog vector is camera-relative, so rotate it by
		// the yaw the client itself reported in this same packet.
		val yawRad = Math.toRadians(pk.rotation.y.toDouble())
		val sinYaw = sin(yawRad).toFloat()
		val cosYaw = cos(yawRad).toFloat()

		val forward = analog.y
		val strafe = analog.x

		var motionX = strafe * cosYaw - forward * sinYaw
		var motionZ = forward * cosYaw + strafe * sinYaw

		// Normalise and scale: any deliberate move input flies at full speed.
		val len = sqrt((motionX * motionX + motionZ * motionZ).toDouble()).toFloat()
		if (len > 0.001f) {
			motionX = motionX / len * horizSpeed
			motionZ = motionZ / len * horizSpeed
		} else {
			motionX = 0f
			motionZ = 0f
		}

		// Collision consistency (see the class doc): never request a path the
		// client itself just reported as blocked.
		if (collisionGuard) {
			if (inputData.contains(PlayerAuthInputData.HORIZONTAL_COLLISION)) {
				motionX = 0f
				motionZ = 0f
			}
			if (vertSpeed < 0f && inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION)) {
				vertSpeed = 0f
			}
		}

		ModuleCombat.motion(session, motionX, vertSpeed, motionZ)
		lastPos = selfPos
	}

	private companion object {
		/** Horizontal ceiling per tick, blocks: `sqrt(5.99)` in the original. */
		const val MAX_HORIZONTAL_PER_TICK = 2.44744f

		/** Position jump, in blocks, that counts as a setback/teleport. */
		const val RUBBERBAND_DISTANCE = 4.5f
	}
}
