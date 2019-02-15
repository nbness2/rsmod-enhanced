package gg.rsmod.game.model.entity

import gg.rsmod.game.action.NpcDeathAction
import gg.rsmod.game.action.PlayerDeathAction
import gg.rsmod.game.message.impl.SetMapFlagMessage
import gg.rsmod.game.model.*
import gg.rsmod.game.model.collision.CollisionManager
import gg.rsmod.game.model.combat.DamageMap
import gg.rsmod.game.model.path.FutureRoute
import gg.rsmod.game.model.path.PathFindingStrategy
import gg.rsmod.game.model.path.PathRequest
import gg.rsmod.game.model.path.Route
import gg.rsmod.game.model.path.strategy.BFSPathFindingStrategy
import gg.rsmod.game.model.path.strategy.SimplePathFindingStrategy
import gg.rsmod.game.model.region.Chunk
import gg.rsmod.game.plugin.Plugin
import gg.rsmod.game.sync.block.UpdateBlockBuffer
import gg.rsmod.game.sync.block.UpdateBlockType
import java.lang.ref.WeakReference
import java.util.*

/**
 * A controllable character in the world that is used by something, or someone,
 * for their own purpose.
 *
 * @author Tom <rspsmods@gmail.com>
 */
abstract class Pawn(val world: World) : Entity() {

    /**
     * The index assigned when this [Pawn] is successfully added to a [PawnList].
     */
    var index = -1

    /**
     * @see UpdateBlockBuffer
     */
    var blockBuffer = UpdateBlockBuffer()

    /**
     * The 3D [Tile] that this pawn was standing on, in the last game cycle.
     */
    var lastTile: Tile? = null

    /**
     * The last tile that was set for the pawn's [gg.rsmod.game.model.region.Chunk].
     */
    var lastChunkTile: Tile? = null

    /**
     * Whether or not this pawn can teleported this game cycle.
     */
    var teleport = false

    /**
     * @see [MovementQueue]
     */
    internal val movementQueue by lazy { MovementQueue(this) }

    /**
     * The current directions that this pawn is moving.
     */
    var steps: MovementQueue.StepDirection? = null

    /**
     * The last [Direction] this pawn was facing.
     */
    var lastFacingDirection: Direction = Direction.SOUTH

    /**
     * The current [LockState] which filters what actions this pawn can perform.
     */
    var lock = LockState.NONE

    /**
     * The attributes attached to the pawn.
     *
     * @see AttributeSystem
     */
    val attr = AttributeSystem()

    /**
     * The timers attached to the pawn.
     *
     * @see TimerSystem
     */
    val timers = TimerSystem()

    /**
     * The equipment bonus for the pawn.
     */
    val equipmentBonuses = IntArray(14)

    /**
     * The current prayer icon that the pawn has active.
     */
    var prayerIcon = -1

    /**
     * Transmog is the action of turning into an npc. This value is equal to the
     * npc id of the npc you want to turn into, visually.
     */
    private var transmogId = -1

    /**
     * A list of pending [Hit]s.
     */
    val pendingHits = arrayListOf<Hit>()

    /**
     * A [DamageMap] to keep track of who has dealt damage to this pawn.
     */
    val damageMap = DamageMap()

    /**
     * A flag which indicates if this pawn is visible to players in the world.
     */
    var invisible = false

    private var futureRoute: FutureRoute? = null

    /**
     * Handles logic before any synchronization tasks are executed.
     */
    abstract fun cycle()

    fun isDead(): Boolean = getCurrentHp() == 0

    fun isAlive(): Boolean = !isDead()

    abstract fun isRunning(): Boolean

    abstract fun getSize(): Int

    abstract fun getCurrentHp(): Int

    abstract fun getMaxHp(): Int

    abstract fun setCurrentHp(level: Int)

    abstract fun addBlock(block: UpdateBlockType)

    abstract fun hasBlock(block: UpdateBlockType): Boolean

    fun getTransmogId(): Int = transmogId

    fun setTransmogId(transmogId: Int) {
        this.transmogId = transmogId
        addBlock(UpdateBlockType.APPEARANCE)
    }

    fun hasMoveDestination(): Boolean = futureRoute != null || movementQueue.hasDestination()

    fun stopMovement() {
        movementQueue.clear()
    }

    fun getCentreTile(): Tile = tile.transform(getSize() shr 1, getSize() shr 1)

    // Credits: Kris#1337
    fun getFrontFacingTile(target: Tile, offset: Int = 0): Tile {
        val size = (getSize() shr 1)
        val centre = getCentreTile()

        val granularity = 2048
        val lutFactor = (granularity / (Math.PI * 2)) // Lookup table factor

        val theta = Math.atan2((target.z - centre.z).toDouble(), (target.x - centre.x).toDouble())
        var angle = Math.toDegrees((((theta * lutFactor).toInt() + offset) and (granularity - 1)) / lutFactor)
        if (angle < 0) {
            angle += 360
        }
        angle = Math.toRadians(angle)

        val tx = Math.round(centre.x + (size * Math.cos(angle))).toInt()
        val tz = Math.round(centre.z + (size * Math.sin(angle))).toInt()
        return Tile(tx, tz, tile.height)
    }

    fun getFrontFacingTile(target: Pawn, offset: Int = 0): Tile = getFrontFacingTile(target.getCentreTile(), offset)

    fun attack(target: Pawn) {
        resetInteractions()
        interruptPlugins()

        attr[COMBAT_TARGET_FOCUS_ATTR] = WeakReference(target)

        /**
         * Players always have the default combat, and npcs will use default
         * combat <strong>unless</strong> they have a custom npc combat plugin
         * bound to their npc id.
         */
        if (getType().isPlayer() || this is Npc && !world.plugins.executeNpcCombat(this)) {
            world.plugins.executeCombat(this)
        }
    }

    fun timerCycle() {
        val timerIterator = timers.getTimers().entries.iterator()
        while (timerIterator.hasNext()) {
            val timer = timerIterator.next()

            if (timer.value <= 0) {
                // NOTE(Tom): if any timer may modify another [Pawn], we will
                // need to iterate timers on a sequential task and execute
                // any of them which have a value (time) of [0], instead of
                // handling it here. This would only apply if we are using
                // a parallel task to call [cycle].
                world.plugins.executeTimer(this, timer.key)
                if (!timers.has(timer.key)) {
                    timerIterator.remove()
                }
            }
        }

        timers.getTimers().entries.forEach { timer ->
            timer.setValue(timer.value - 1)
        }
    }

    fun hitsCycle() {
        val hitIterator = pendingHits.iterator()
        iterator@ while (hitIterator.hasNext()) {
            if (isDead()) {
                break
            }
            val hit = hitIterator.next()

            if (lock.delaysDamage()) {
                hit.damageDelay = Math.max(0, hit.damageDelay - 1)
                continue
            }

            if (--hit.damageDelay <= 0) {
                if (!hit.cancelCondition()) {
                    blockBuffer.hits.add(hit)
                    addBlock(UpdateBlockType.HITMARK)

                    for (hitmark in hit.hitmarks) {
                        val hp = getCurrentHp()
                        if (hitmark.damage > hp) {
                            hitmark.damage = hp
                        }
                        if (INFINITE_VARS_STORAGE.get(this, InfiniteVarsType.HP) == 0) {
                            setCurrentHp(hp - hitmark.damage)
                        }
                        if (getCurrentHp() <= 0) {
                            hit.actions.forEach { action -> action() }
                            interruptPlugins()
                            if (getType().isPlayer()) {
                                executePlugin(PlayerDeathAction.deathPlugin)
                            } else {
                                executePlugin(NpcDeathAction.deathPlugin)
                            }
                            hitIterator.remove()
                            break@iterator
                        }
                    }
                    hit.actions.forEach { action -> action() }
                }
                hitIterator.remove()
            }
        }
        if (isDead()) {
            pendingHits.clear()
        }
    }

    fun handleFutureRoute() {
        if (futureRoute?.completed == true && futureRoute?.strategy?.cancel == false) {
            val futureRoute = futureRoute!!
            walkPath(futureRoute.route.path, futureRoute.stepType)
            this.futureRoute = null
        }
    }

    fun walkPath(path: ArrayDeque<Tile>, stepType: MovementQueue.StepType) {
        if (path.isEmpty()) {
            if (this is Player) {
                write(SetMapFlagMessage(255, 255))
            }
            return
        }

        var tail: Tile? = null
        var next = path.poll()
        while (next != null) {
            movementQueue.addStep(next, stepType)
            val poll = path.poll()
            if (poll == null) {
                tail = next
            }
            next = poll
        }

        /**
         * If the tail is null (should never be unless we mess with code above), or
         * if the tail is the tile we're standing on, then we don't have to move at all!
         */
        if (tail == null || tail.sameAs(tile)) {
            if (this is Player) {
                write(SetMapFlagMessage(255, 255))
            }
            movementQueue.clear()
            return
        }

        if (this is Player && lastKnownRegionBase != null) {
            write(SetMapFlagMessage(tail.x - lastKnownRegionBase!!.x, tail.z - lastKnownRegionBase!!.z))
        }
    }

    fun walkTo(tile: Tile, stepType: MovementQueue.StepType, projectilePath: Boolean = false) = walkTo(tile.x, tile.z, stepType, projectilePath)

    fun walkTo(x: Int, z: Int, stepType: MovementQueue.StepType, projectilePath: Boolean = false) {
        val multiThread = world.multiThreadPathFinding
        val request = PathRequest.buildWalkRequest(this, x, z, projectilePath)
        val strategy = createPathFindingStrategy(copyChunks = multiThread)

        /**
         * When using multi-thread path-finding, the [PathRequest.buildWalkRequest]
         * must have the [tile] in sync with the game-thread, so we need to make sure
         * that in this cycle, the pawn's [tile] does not change. The easiest way to
         * do this is by clearing their movement queue. Though it can cause weird
         */
        if (multiThread) {
            movementQueue.clear()
        }
        futureRoute?.strategy?.cancel = true

        if (multiThread) {
            futureRoute = FutureRoute.of(strategy, request, stepType)
        } else {
            val route = strategy.calculateRoute(request)
            walkPath(route.path, stepType)
        }
    }

    suspend fun walkTo(it: Plugin, tile: Tile, stepType: MovementQueue.StepType, projectilePath: Boolean = false) = walkTo(it, tile.x, tile.z, stepType, projectilePath)

    suspend fun walkTo(it: Plugin, x: Int, z: Int, stepType: MovementQueue.StepType, projectilePath: Boolean = false): Route {
        val multiThread = world.multiThreadPathFinding
        val request = PathRequest.buildWalkRequest(this, x, z, projectilePath)
        val strategy = createPathFindingStrategy(copyChunks = multiThread)

        if (multiThread) {
            movementQueue.clear()
        }
        movementQueue.clear()
        futureRoute?.strategy?.cancel = true

        if (multiThread) {
            futureRoute = FutureRoute.of(strategy, request, stepType)
            while (!futureRoute!!.completed) {
                it.wait(1)
            }
            return futureRoute!!.route
        }

        val route = strategy.calculateRoute(request)
        walkPath(route.path, stepType)
        return route
    }

    fun teleport(x: Int, z: Int, height: Int = 0) {
        teleport = true
        tile = Tile(x, z, height)
        movementQueue.clear()
        addBlock(UpdateBlockType.MOVEMENT)
    }

    fun teleport(tile: Tile) {
        teleport(tile.x, tile.z, tile.height)
    }

    fun animate(id: Int) {
        blockBuffer.animation = id
        addBlock(UpdateBlockType.ANIMATION)
    }

    fun graphic(id: Int, height: Int = 0, delay: Int = 0) {
        blockBuffer.graphicId = id
        blockBuffer.graphicHeight = height
        blockBuffer.graphicDelay = delay
        addBlock(UpdateBlockType.GFX)
    }

    fun graphic(graphic: Graphic) {
        graphic(graphic.id, graphic.height, graphic.delay)
    }

    fun forceChat(message: String) {
        blockBuffer.forceChat = message
        addBlock(UpdateBlockType.FORCE_CHAT)
    }

    fun faceTile(face: Tile, width: Int = 1, length: Int = 1) {
        if (getType().isPlayer()) {
            val srcX = tile.x * 64
            val srcZ = tile.z * 64
            val dstX = face.x * 64
            val dstZ = face.z * 64

            var degreesX = (srcX - dstX).toDouble()
            var degreesZ = (srcZ - dstZ).toDouble()

            degreesX += (Math.floor(width / 2.0)) * 32
            degreesZ += (Math.floor(length / 2.0)) * 32

            blockBuffer.faceDegrees = (Math.atan2(degreesX, degreesZ) * 325.949).toInt() and 0x7ff
        } else if (getType().isNpc()) {
            blockBuffer.faceDegrees = (face.x shl 16) or face.z
        }

        blockBuffer.facePawnIndex = -1
        addBlock(UpdateBlockType.FACE_TILE)
    }

    fun facePawn(pawn: Pawn?) {
        blockBuffer.faceDegrees = 0

        val index = if (pawn == null) -1 else if (pawn.getType().isPlayer()) pawn.index + 32768 else pawn.index
        if (blockBuffer.facePawnIndex != index) {
            blockBuffer.facePawnIndex = index
            addBlock(UpdateBlockType.FACE_PAWN)
        }
    }

    fun resetInteractions() {
        attr.remove(COMBAT_TARGET_FOCUS_ATTR)
        facePawn(null)
    }

    /**
     * Executes a plugin with this [Pawn] as its context.
     */
    fun executePlugin(plugin: Function1<Plugin, Unit>) {
        world.pluginExecutor.execute(this, plugin)
    }

    /**
     * Terminates any on-going plugins that are being executed by this [Pawn].
     */
    fun interruptPlugins() {
        world.pluginExecutor.interruptPluginsWithContext(this)
    }

    fun createPathFindingStrategy(copyChunks: Boolean = false): PathFindingStrategy {
        val collision: CollisionManager = if (copyChunks) {
            val chunks = world.chunks.copyChunksWithinRadius(tile.toChunkCoords(), height = tile.height, radius = Chunk.CHUNK_VIEW_RADIUS)
            CollisionManager(chunks, createChunksIfNeeded = false)
        } else {
            world.collision
        }
        return if (getType().isPlayer()) BFSPathFindingStrategy(collision) else SimplePathFindingStrategy(collision)
    }
}
