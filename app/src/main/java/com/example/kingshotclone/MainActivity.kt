package com.example.kingshotclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.*
import kotlin.random.Random

// ═══════════════════════════════════════════════
//  ENUMS & MODELS
// ═══════════════════════════════════════════════

enum class ParticleType { SPARK, SMOKE, RING }

data class Particle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float,
    val color: Color,
    val size: Float,
    val type: ParticleType = ParticleType.SPARK
)

enum class EnemyType(
    val color: Color, val hp: Int, val speed: Float,
    val scale: Float, val reward: Int, val dmgToBase: Int
) {
    VOID_WALKER(Color(0xFFBB86FC), 150,  2.5f, 1.0f,  50, 1),
    SPECTER    (Color(0xFF18FFFF), 90,   5.0f, 0.8f,  40, 1),
    IRON_GOLEM (Color(0xFF90A4AE), 800,  1.1f, 1.7f, 150, 2),
    DRAGON_BORN(Color(0xFFFFD600), 8000, 0.6f, 3.2f, 2500, 5)
}

data class Enemy(
    var progress: Float = 0f,
    var x: Float = 0f, var y: Float = 0f,
    val type: EnemyType,
    var hp: Int = type.hp,
    val id: Long = Random.nextLong(),
    var hitFlash: Float = 0f,
    var frozen: Float = 0f
)

enum class TowerType(
    val label: String, val color: Color, val cost: Int,
    val range: Float, val dmg: Int, val fireRate: Long
) {
    TESLA ("TESLA",  Color(0xFF00E5FF), 200, 700f,  30, 320),
    NOVA  ("NOVA",   Color(0xFFFF1744), 550, 430f, 190, 1050),
    CANNON("CANNON", Color(0xFFFF9100), 350, 600f, 115, 760),
    FREEZE("FREEZE", Color(0xFF40C4FF), 400, 500f,   8, 660)
}

data class Tower(
    val x: Float, val y: Float,
    val type: TowerType,
    var angle: Float = 0f,
    var lastShot: Long = 0,
    var animState: Float = 0f,
    var level: Int = 1
)

data class Projectile(
    var x: Float, var y: Float,
    val targetId: Long,
    val color: Color,
    val dmg: Int,
    val source: TowerType,
    val trail: ArrayDeque<Offset> = ArrayDeque()
)

enum class GameState { PLAYING, WAVE_BREAK, GAME_OVER }

// ═══════════════════════════════════════════════
//  PATH SYSTEM
// ═══════════════════════════════════════════════

data class PNode(val x: Float, val y: Float)

fun makePath(w: Float, h: Float) = listOf(
    PNode(-120f,     h * 0.18f),
    PNode(w * 0.22f, h * 0.18f),
    PNode(w * 0.22f, h * 0.42f),
    PNode(w * 0.58f, h * 0.42f),
    PNode(w * 0.58f, h * 0.20f),
    PNode(w * 0.84f, h * 0.20f),
    PNode(w * 0.84f, h * 0.64f),
    PNode(w * 0.44f, h * 0.64f),
    PNode(w * 0.44f, h * 0.84f),
    PNode(w + 120f,  h * 0.84f)
)

fun pathPos(path: List<PNode>, t: Float): Offset {
    if (path.size < 2) return Offset.Zero
    val n = path.size - 1
    val s = t.coerceIn(0f, 1f) * n
    val i = s.toInt().coerceIn(0, n - 1)
    val f = s - i
    return Offset(
        path[i].x + (path[i + 1].x - path[i].x) * f,
        path[i].y + (path[i + 1].y - path[i].y) * f
    )
}

// ═══════════════════════════════════════════════
//  GAME ENGINE
// ═══════════════════════════════════════════════

class GameEngine {
    val enemies     = mutableStateListOf<Enemy>()
    val towers      = mutableStateListOf<Tower>()
    val projectiles = mutableStateListOf<Projectile>()
    val particles   = mutableStateListOf<Particle>()

    var gold        by mutableIntStateOf(800)
    var health      by mutableIntStateOf(20)
    var wave        by mutableIntStateOf(1)
    var score       by mutableIntStateOf(0)
    var state       by mutableStateOf(GameState.WAVE_BREAK)
    var breakTimer  by mutableFloatStateOf(5f)
    var screenShake by mutableFloatStateOf(0f)
    var chromatic   by mutableFloatStateOf(0f)
    var globalTime  = 0f

    var path = listOf<PNode>()
    private var sw = 0f; private var sh = 0f
    private var spawned = 0; private var waveMax = 0
    private var spawnCd = 0f

    fun init(w: Float, h: Float) {
        if (sw == w && sh == h) return
        sw = w; sh = h; path = makePath(w, h)
    }

    fun update(dt: Float) {
        if (state == GameState.GAME_OVER) return
        globalTime += dt
        screenShake *= 0.82f
        chromatic   *= 0.88f

        if (state == GameState.WAVE_BREAK) {
            breakTimer -= dt
            if (breakTimer <= 0f) {
                waveMax = 6 + wave * 5; spawned = 0; spawnCd = 0f
                state = GameState.PLAYING
            }
            return
        }

        tickParticles(dt)
        tickEnemies(dt)
        tickTowers()
        tickProjectiles()

        if (spawned >= waveMax && enemies.isEmpty() && projectiles.isEmpty()) {
            gold += 200 + wave * 50
            wave++
            breakTimer = 8f
            state = GameState.WAVE_BREAK
        }
    }

    private fun tickParticles(dt: Float) {
        val iter = particles.listIterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx; p.y += p.vy
            if (p.type == ParticleType.SPARK) p.vy += 0.25f
            p.life -= dt
            if (p.life <= 0f) iter.remove()
        }
    }

    private fun tickEnemies(dt: Float) {
        if (spawned < waveMax) {
            spawnCd -= dt
            if (spawnCd <= 0f) {
                val t = when {
                    wave >= 8 && Random.nextFloat() < 0.07f -> EnemyType.DRAGON_BORN
                    wave >= 4 && Random.nextFloat() < 0.22f -> EnemyType.SPECTER
                    Random.nextFloat() < 0.28f              -> EnemyType.IRON_GOLEM
                    else                                    -> EnemyType.VOID_WALKER
                }
                val e = Enemy(type = t)
                val p0 = pathPos(path, 0f)
                e.x = p0.x; e.y = p0.y
                enemies.add(e)
                spawned++
                spawnCd = 1.4f / (1f + wave * 0.04f)
            }
        }

        val iter = enemies.listIterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val spd = if (e.frozen > 0) e.type.speed * 0.25f else e.type.speed
            e.progress += spd * dt * 0.007f
            if (e.frozen > 0) e.frozen -= dt
            if (e.hitFlash > 0) e.hitFlash -= dt * 3f
            val pos = pathPos(path, e.progress)
            e.x = pos.x; e.y = pos.y
            if (e.progress >= 1f) {
                health = (health - e.type.dmgToBase).coerceAtLeast(0)
                if (health == 0) { state = GameState.GAME_OVER }
                screenShake = 55f; chromatic = 22f
                boom(e.x, e.y, Color(0xFFFF1744), 70)
                iter.remove()
            }
        }
    }

    private fun tickTowers() {
        val now = System.currentTimeMillis()
        towers.forEach { t ->
            val r = t.type.range * (1f + (t.level - 1) * 0.2f)
            val tgt = enemies
                .filter { dist(it.x, it.y, t.x, t.y) <= r }
                .maxByOrNull { it.progress } ?: return@forEach

            t.angle = atan2(tgt.y - t.y, tgt.x - t.x) * 180f / PI.toFloat() + 90f
            val fr = (t.type.fireRate / (1f + (t.level - 1) * 0.2f)).toLong()
            if (now - t.lastShot >= fr) {
                projectiles.add(Projectile(t.x, t.y, tgt.id, t.type.color, t.type.dmg * t.level, t.type))
                t.lastShot = now; t.animState = 1f
            }
            if (t.animState > 0) t.animState = (t.animState - 0.05f).coerceAtLeast(0f)
        }
    }

    private fun tickProjectiles() {
        val iter = projectiles.listIterator()
        while (iter.hasNext()) {
            val p = iter.next()
            val tgt = enemies.find { it.id == p.targetId }
            if (tgt == null) { iter.remove(); continue }

            val dx = tgt.x - p.x; val dy = tgt.y - p.y
            val d = sqrt(dx * dx + dy * dy)
            p.trail.addFirst(Offset(p.x, p.y))
            if (p.trail.size > 9) p.trail.removeLast()
            p.x += dx / d * 40f; p.y += dy / d * 40f

            if (d < 50f) {
                when (p.source) {
                    TowerType.NOVA -> {
                        enemies.filter { dist(it.x, it.y, p.x, p.y) < 220f }
                            .forEach { e -> e.hp -= p.dmg; e.hitFlash = 1f }
                        boom(p.x, p.y, p.color, 60)
                        ring(p.x, p.y, p.color)
                        screenShake = 16f
                    }
                    TowerType.FREEZE -> {
                        tgt.hp -= p.dmg; tgt.hitFlash = 1f; tgt.frozen = 3.5f
                        boom(p.x, p.y, p.color, 20)
                    }
                    else -> {
                        tgt.hp -= p.dmg; tgt.hitFlash = 1f
                        boom(p.x, p.y, p.color, 18)
                    }
                }
                val dead = enemies.filter { it.hp <= 0 }
                dead.forEach { e ->
                    gold += e.type.reward
                    score += e.type.reward * 10
                    boom(e.x, e.y, e.type.color, 55)
                    if (e.type == EnemyType.DRAGON_BORN) screenShake = 40f
                }
                enemies.removeAll { it.hp <= 0 }
                iter.remove()
            }
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))

    private fun boom(x: Float, y: Float, c: Color, n: Int) {
        repeat(n) {
            val a = Random.nextFloat() * 2 * PI.toFloat()
            val spd = Random.nextFloat() * 10f + 2f
            particles.add(Particle(x, y, cos(a) * spd, sin(a) * spd,
                0.7f + Random.nextFloat() * 0.6f, c, Random.nextFloat() * 7f + 3f))
            if (Random.nextFloat() > 0.62f)
                particles.add(Particle(x, y, Random.nextFloat() * 4f - 2f,
                    -Random.nextFloat() * 2f - 0.5f, 1.3f, Color.Gray.copy(0.45f), 18f, ParticleType.SMOKE))
        }
    }

    private fun ring(x: Float, y: Float, c: Color) {
        repeat(32) {
            val a = it / 32f * 2 * PI.toFloat()
            particles.add(Particle(x, y, cos(a) * 9f, sin(a) * 9f, 0.5f, c, 5f, ParticleType.RING))
        }
    }

    fun build(x: Float, y: Float, type: TowerType): Boolean {
        if (gold < type.cost) return false
        // block on path
        for (i in 0 until path.size - 1) {
            val ax = path[i].x; val ay = path[i].y
            val bx = path[i + 1].x; val by = path[i + 1].y
            val dx = bx - ax; val dy2 = by - ay
            val lenSq = dx * dx + dy2 * dy2
            if (lenSq == 0f) continue
            val tc = ((x - ax) * dx + (y - ay) * dy2) / lenSq
            val cx = ax + tc.coerceIn(0f, 1f) * dx
            val cy = ay + tc.coerceIn(0f, 1f) * dy2
            if (dist(x, y, cx, cy) < 92f) return false
        }
        if (towers.any { dist(it.x, it.y, x, y) < 88f }) return false
        towers.add(Tower(x, y, type))
        gold -= type.cost
        boom(x, y, type.color, 30)
        return true
    }

    fun upgrade(x: Float, y: Float): Boolean {
        val t = towers.minByOrNull { dist(it.x, it.y, x, y) } ?: return false
        if (dist(t.x, t.y, x, y) > 80f || t.level >= 3) return false
        val cost = t.type.cost
        if (gold < cost) return false
        val idx = towers.indexOf(t)
        towers[idx] = t.copy(level = t.level + 1)
        gold -= cost
        boom(t.x, t.y, Color.White, 45)
        return true
    }

    fun reset() {
        enemies.clear(); towers.clear(); projectiles.clear(); particles.clear()
        gold = 800; health = 20; wave = 1; score = 0
        screenShake = 0f; chromatic = 0f; globalTime = 0f
        spawned = 0; state = GameState.WAVE_BREAK; breakTimer = 4f
    }
}

// ═══════════════════════════════════════════════
//  SHAPE HELPERS
// ═══════════════════════════════════════════════

fun ngon(cx: Float, cy: Float, r: Float, n: Int, rotDeg: Float = 0f): Path {
    val p = Path()
    for (i in 0 until n) {
        val a = (i.toFloat() / n) * 2f * PI.toFloat() + rotDeg * PI.toFloat() / 180f
        val x = cx + cos(a) * r; val y = cy + sin(a) * r
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close(); return p
}

fun star(cx: Float, cy: Float, r1: Float, r2: Float, pts: Int, rotDeg: Float = 0f): Path {
    val p = Path()
    for (i in 0 until pts * 2) {
        val a = (i.toFloat() / (pts * 2)) * 2f * PI.toFloat() + rotDeg * PI.toFloat() / 180f
        val r = if (i % 2 == 0) r1 else r2
        val x = cx + cos(a) * r; val y = cy + sin(a) * r
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close(); return p
}

// ═══════════════════════════════════════════════
//  DRAWING FUNCTIONS (DrawScope extensions)
// ═══════════════════════════════════════════════

fun DrawScope.drawBackground(path: List<PNode>, t: Float) {
    // Deep space base
    drawRect(Brush.verticalGradient(listOf(Color(0xFF02020E), Color(0xFF05050F), Color(0xFF020210))))

    // Hex grid — cyberpunk floor
    val hexR = 38f
    val hexW = hexR * sqrt(3f)
    val hexH = hexR * 2f
    val cols2 = (size.width / hexW).toInt() + 3
    val rows2 = (size.height / (hexH * 0.75f)).toInt() + 3
    for (row in -1..rows2) {
        for (col in -1..cols2) {
            val xOff = if (row % 2 == 0) 0f else hexW / 2f
            val cx = col * hexW + xOff
            val cy = row * hexH * 0.75f
            val hexPath = ngon(cx, cy, hexR * 0.92f, 6, 0f)
            drawPath(hexPath, Color(0xFF0A0A20), style = Stroke(0.7f))
        }
    }

    // Ambient corner glows
    drawCircle(Brush.radialGradient(listOf(Color(0x1500E5FF), Color.Transparent)), 380f, Offset(0f, size.height * 0.3f))
    drawCircle(Brush.radialGradient(listOf(Color(0x12FF1744), Color.Transparent)), 340f, Offset(size.width, size.height * 0.7f))

    if (path.size < 2) return

    // Path: dark asphalt lane with neon edge lines
    for (i in 0 until path.size - 1) {
        val a = Offset(path[i].x, path[i].y)
        val b = Offset(path[i + 1].x, path[i + 1].y)
        // Wide dark road base
        drawLine(Color(0xFF0C0C1A), a, b, 96f, cap = StrokeCap.Square)
        // Road texture lines
        drawLine(Color(0xFF111128), a, b, 80f, cap = StrokeCap.Square)
        // Outer glow
        drawLine(Color(0x22FF9800), a, b, 110f, cap = StrokeCap.Round)
        // Neon edge strips
        drawLine(Color(0xCCFF9800), a, b,   3f, cap = StrokeCap.Round)
        // Center dashed line — draw as full line (dashes via particles not possible easily)
        drawLine(Color(0x55FFD740), a, b,   1.5f, cap = StrokeCap.Round,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 18f)))
    }

    // Animated energy pulse traveling along path
    repeat(7) { k ->
        val pct = ((t * 0.32f + k.toFloat() / 7f) % 1f)
        val pos = pathPos(path, pct)
        val gAlpha = (0.55f + 0.35f * sin(t * 3f + k * 1.3f)).coerceIn(0f, 1f)
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFFFB300).copy(gAlpha * 0.9f), Color(0xFFFF6F00).copy(gAlpha * 0.3f), Color.Transparent),
                Offset(pos.x, pos.y), 28f
            ), 28f, Offset(pos.x, pos.y)
        )
    }
}

fun DrawScope.drawEnemy(e: Enemy, t: Float) {
    val s = e.type.scale
    val bob = sin(t * 3.8f + e.id.toFloat()) * 5f
    val flash = e.hitFlash > 0.2f
    val c = if (flash) Color.White else e.type.color
    val hpR = (e.hp.toFloat() / e.type.hp).coerceIn(0f, 1f)

    withTransform({ translate(e.x, e.y + bob) }) {

        when (e.type) {
            // ── VOID WALKER: ghost pentagon with tendrils ──
            EnemyType.VOID_WALKER -> {
                val rot = t * 45f
                // outer halo glow
                drawCircle(Brush.radialGradient(listOf(c.copy(0.18f), Color.Transparent)), 68f * s, Offset.Zero)
                // spinning outer ring
                withTransform({ rotate(rot, Offset.Zero) }) {
                    drawPath(ngon(0f, 0f, 50f * s, 5), Color.Transparent)
                    drawPath(ngon(0f, 0f, 50f * s, 5), c.copy(0.55f), style = Stroke(2.5f))
                }
                // body pentagon
                drawPath(ngon(0f, 0f, 36f * s, 5, -rot * 0.4f), c.copy(0.85f))
                // dark void center
                drawPath(ngon(0f, 0f, 22f * s, 5, -rot * 0.4f), Color(0xFF020210))
                // pulsing eye
                val eyeR = 8f * s * (0.75f + sin(t * 8f) * 0.25f)
                drawCircle(c, eyeR)
                drawCircle(Color.White.copy(0.9f), eyeR * 0.4f)
                // three rotating tendrils
                repeat(3) { i ->
                    val ta = rot * PI.toFloat() / 180f + i * 2f * PI.toFloat() / 3f
                    val tx = cos(ta) * 54f * s; val ty = sin(ta) * 54f * s
                    drawLine(c.copy(0.4f), Offset(0f, 0f), Offset(tx, ty), 2f, cap = StrokeCap.Round)
                    drawCircle(c.copy(0.6f), 5f * s, Offset(tx, ty))
                }
            }

            // ── SPECTER: fast diamond with motion streak ──
            EnemyType.SPECTER -> {
                val spin = t * 120f
                // motion blur streaks above
                drawLine(c.copy(0.15f), Offset(0f, -20f * s), Offset(0f, -80f * s), 18f * s, cap = StrokeCap.Round)
                drawLine(c.copy(0.08f), Offset(-8f * s, -15f * s), Offset(-8f * s, -60f * s), 8f * s, cap = StrokeCap.Round)
                drawLine(c.copy(0.08f), Offset(8f * s, -15f * s), Offset(8f * s, -60f * s), 8f * s, cap = StrokeCap.Round)
                // outer glow
                drawCircle(Brush.radialGradient(listOf(c.copy(0.22f), Color.Transparent)), 56f * s, Offset.Zero)
                // spinning outer diamond
                withTransform({ rotate(spin, Offset.Zero) }) {
                    drawPath(ngon(0f, 0f, 42f * s, 4, 0f), c.copy(0.3f), style = Stroke(3f))
                }
                // solid inner diamond (counter-spin)
                withTransform({ rotate(-spin * 0.5f + 45f, Offset.Zero) }) {
                    drawPath(ngon(0f, 0f, 28f * s, 4, 0f), c)
                    drawPath(ngon(0f, 0f, 16f * s, 4, 0f), Color(0xFF020210))
                }
                drawCircle(c, 5f * s * (0.8f + sin(t * 12f) * 0.2f))
                drawCircle(Color.White.copy(0.95f), 2.5f * s)
            }

            // ── IRON GOLEM: armored hexagon with plating ──
            EnemyType.IRON_GOLEM -> {
                val spin = t * 18f
                // outer aura
                drawCircle(Brush.radialGradient(listOf(c.copy(0.12f), Color.Transparent)), 115f * s, Offset.Zero)
                // outer armor ring
                withTransform({ rotate(spin, Offset.Zero) }) {
                    drawPath(ngon(0f, 0f, 88f * s, 6), Color(0xFF1A2228))
                    drawPath(ngon(0f, 0f, 88f * s, 6), c.copy(0.5f), style = Stroke(10f))
                    // armor bolt slots
                    repeat(6) { i ->
                        val ba = i * PI.toFloat() / 3f + spin * PI.toFloat() / 180f
                        val bx = cos(ba) * 76f * s; val by = sin(ba) * 76f * s
                        drawCircle(c.copy(0.7f), 6f * s, Offset(bx, by))
                        drawCircle(Color(0xFF0A0A1A), 3f * s, Offset(bx, by))
                    }
                }
                // inner body
                drawPath(ngon(0f, 0f, 58f * s, 6), Color(0xFF0E1820))
                drawPath(ngon(0f, 0f, 58f * s, 6), c.copy(if (flash) 0.9f else 0.65f), style = Stroke(8f))
                // chest plate detail
                drawPath(ngon(0f, 0f, 34f * s, 6, 30f), Color(0xFF151E26))
                drawPath(ngon(0f, 0f, 34f * s, 6, 30f), c.copy(0.3f), style = Stroke(3f))
                // reactor core
                drawCircle(c, 15f * s * (0.9f + sin(t * 6f) * 0.1f))
                drawCircle(Color.White.copy(0.6f), 6f * s)
            }

            // ── DRAGON BORN: massive boss with orbiting satellites ──
            EnemyType.DRAGON_BORN -> {
                val spin = t * 22f
                // massive outer glow
                drawCircle(Brush.radialGradient(listOf(c.copy(0.08f), c.copy(0.04f), Color.Transparent)), 220f * s, Offset.Zero)
                // three orbiting satellite orbs
                repeat(3) { i ->
                    val oa = spin * PI.toFloat() / 180f + i * 2f * PI.toFloat() / 3f
                    val ox = cos(oa) * 140f * s; val oy = sin(oa) * 140f * s
                    drawCircle(c.copy(0.35f), 20f * s, Offset(ox, oy))
                    drawCircle(c.copy(0.7f), 14f * s, Offset(ox, oy))
                    drawCircle(Color.White.copy(0.5f), 5f * s, Offset(ox, oy))
                    drawLine(c.copy(0.15f), Offset.Zero, Offset(ox, oy), 2f)
                }
                // outer star ring
                withTransform({ rotate(spin * 0.6f, Offset.Zero) }) {
                    drawPath(star(0f, 0f, 115f * s, 70f * s, 8), c.copy(0.18f), style = Stroke(4f))
                }
                withTransform({ rotate(-spin * 0.4f, Offset.Zero) }) {
                    drawPath(ngon(0f, 0f, 100f * s, 8), c.copy(0.25f), style = Stroke(5f))
                }
                // heavy body
                drawCircle(Color(0xFF0A0A05), 78f * s)
                drawCircle(c.copy(if (flash) 0.9f else 0.72f), 78f * s, style = Stroke(12f))
                // inner details
                withTransform({ rotate(-spin * 1.5f, Offset.Zero) }) {
                    drawPath(star(0f, 0f, 52f * s, 32f * s, 6), c.copy(0.22f))
                }
                drawCircle(c, 32f * s * (0.88f + sin(t * 5f) * 0.12f))
                drawCircle(Color(0xFF020210), 18f * s)
                val eyePulse = 9f * s * (0.8f + sin(t * 9f) * 0.2f)
                drawCircle(Color.White, eyePulse)
                drawCircle(c, eyePulse * 0.55f)
            }
        }

        // Freeze overlay
        if (e.frozen > 0) {
            withTransform({ rotate(t * 60f, Offset.Zero) }) {
                drawPath(ngon(0f, 0f, 60f * s, 6), Color(0x4440C4FF), style = Stroke(4f))
            }
            drawCircle(Color(0x2240C4FF), 50f * s)
        }

        // HP bar — floating above enemy
        val bw = 90f * s.coerceAtMost(2f); val bh = 8f
        val barY = -(e.type.scale * 120f + 14f)
        // bar shadow
        drawRoundRect(Color.Black.copy(0.8f), Offset(-bw / 2f - 1f, barY - 1f), Size(bw + 2f, bh + 2f), CornerRadius(5f))
        // bar track
        drawRoundRect(Color(0xFF0A0A15), Offset(-bw / 2f, barY), Size(bw, bh), CornerRadius(4f))
        // bar fill — color shifts red→yellow→green based on HP
        if (hpR > 0f) {
            val barColor = when {
                hpR > 0.6f -> Color(0xFF00E676)
                hpR > 0.3f -> Color(0xFFFFD740)
                else        -> Color(0xFFFF1744)
            }
            drawRoundRect(barColor, Offset(-bw / 2f, barY), Size(bw * hpR, bh), CornerRadius(4f))
            // bar shine
            drawRoundRect(Color.White.copy(0.2f), Offset(-bw / 2f, barY), Size(bw * hpR, bh / 2f), CornerRadius(4f))
        }
    }
}

fun DrawScope.drawTower(t: Tower, time: Float) {
    withTransform({ translate(t.x, t.y) }) {
        val pulse = 1f + sin(time * 5f) * 0.06f
        val lc = when (t.level) { 2 -> Color(0xFFFFD740); 3 -> Color(0xFFFF6D00); else -> t.type.color }

        // Foundation platform — hexagonal base
        drawPath(ngon(0f, 0f, 58f, 6, 0f), Color(0xFF0D0D20))
        drawPath(ngon(0f, 0f, 58f, 6, 0f), lc.copy(0.45f * pulse), style = Stroke(3f + t.level * 1.5f))

        // Platform inner detail
        drawPath(ngon(0f, 0f, 44f, 6, 30f), Color(0xFF141428))
        drawPath(ngon(0f, 0f, 44f, 6, 30f), t.type.color.copy(0.25f), style = Stroke(2f))

        // Level indicators — glowing corner diamonds
        for (i in 0 until t.level) {
            val la = (i.toFloat() / 3f) * 2f * PI.toFloat() - PI.toFloat() / 2f
            val lx = cos(la) * 50f; val ly = sin(la) * 50f
            withTransform({ translate(lx, ly); rotate(45f, Offset.Zero) }) {
                drawRect(lc, Offset(-5f, -5f), Size(10f, 10f))
                drawRect(lc.copy(0.4f), Offset(-5f, -5f), Size(10f, 10f), style = Stroke(1.5f))
            }
        }

        // Range ring (faint) — always visible
        drawCircle(t.type.color.copy(0.04f), t.type.range)
        drawCircle(t.type.color.copy(0.07f), t.type.range, style = Stroke(1f))

        // Rotating barrel / weapon
        withTransform({ rotate(t.angle, Offset.Zero) }) {
            when (t.type) {

                // TESLA: twin coil prongs
                TowerType.TESLA -> {
                    val recoil = t.animState * 14f
                    // barrel shaft
                    drawRect(Color(0xFF0A1520), Offset(-8f, -78f - recoil), Size(16f, 80f))
                    drawRect(t.type.color.copy(0.7f), Offset(-8f, -78f - recoil), Size(16f, 80f), style = Stroke(1.5f))
                    // left prong
                    drawLine(t.type.color.copy(0.8f), Offset(-12f, -68f - recoil), Offset(-20f, -94f - recoil), 3f, cap = StrokeCap.Round)
                    // right prong
                    drawLine(t.type.color.copy(0.8f), Offset(12f, -68f - recoil), Offset(20f, -94f - recoil), 3f, cap = StrokeCap.Round)
                    // energy ball at tip
                    drawCircle(t.type.color, 8f * pulse, Offset(0f, -82f - recoil))
                    drawCircle(Color.White.copy(0.9f), 4f + t.animState * 4f, Offset(0f, -84f - recoil))
                    if (t.animState > 0.3f) {
                        // lightning arcs on fire
                        repeat(4) { i ->
                            val la = i * PI.toFloat() / 2f
                            drawLine(t.type.color.copy(t.animState * 0.7f),
                                Offset(0f, -84f - recoil),
                                Offset(cos(la) * 18f * t.animState, -84f - recoil + sin(la) * 18f * t.animState),
                                1.5f, cap = StrokeCap.Round)
                        }
                    }
                }

                // NOVA: wide plasma cannon
                TowerType.NOVA -> {
                    val recoil = t.animState * 20f
                    // wide hull
                    drawRect(Color(0xFF1A0507), Offset(-14f, -72f - recoil), Size(28f, 76f))
                    drawRect(t.type.color.copy(0.65f), Offset(-14f, -72f - recoil), Size(28f, 76f), style = Stroke(2.5f))
                    // side fins
                    drawRect(Color(0xFF260808), Offset(-22f, -58f - recoil), Size(8f, 40f))
                    drawRect(Color(0xFF260808), Offset(14f, -58f - recoil), Size(8f, 40f))
                    drawRect(t.type.color.copy(0.4f), Offset(-22f, -58f - recoil), Size(8f, 40f), style = Stroke(1.5f))
                    drawRect(t.type.color.copy(0.4f), Offset(14f, -58f - recoil), Size(8f, 40f), style = Stroke(1.5f))
                    // plasma chamber window
                    drawCircle(t.type.color.copy(0.35f), 11f, Offset(0f, -48f - recoil))
                    drawCircle(t.type.color, 7f * (0.85f + t.animState * 0.15f), Offset(0f, -48f - recoil))
                    // muzzle glow
                    drawCircle(t.type.color.copy(0.5f + t.animState * 0.5f), 14f, Offset(0f, -72f - recoil))
                    drawCircle(Color.White.copy(0.8f * (0.2f + t.animState * 0.8f)), 6f + t.animState * 8f, Offset(0f, -74f - recoil))
                }

                // CANNON: heavy artillery
                TowerType.CANNON -> {
                    val recoil = t.animState * 22f
                    // thick barrel with reinforcement rings
                    drawRect(Color(0xFF120A00), Offset(-12f, -90f - recoil), Size(24f, 95f))
                    drawRect(t.type.color.copy(0.55f), Offset(-12f, -90f - recoil), Size(24f, 95f), style = Stroke(2f))
                    // reinforcement bands
                    repeat(4) { i ->
                        val ry = -22f - i * 18f - recoil
                        drawRect(t.type.color.copy(0.45f), Offset(-15f, ry), Size(30f, 7f))
                    }
                    // breach block at base
                    drawRect(Color(0xFF1E1000), Offset(-18f, -16f - recoil), Size(36f, 22f))
                    drawRect(t.type.color.copy(0.5f), Offset(-18f, -16f - recoil), Size(36f, 22f), style = Stroke(2f))
                    // muzzle flash
                    if (t.animState > 0.4f) {
                        drawCircle(t.type.color.copy(t.animState * 0.6f), 20f * t.animState, Offset(0f, -92f - recoil))
                        drawCircle(Color.White.copy(t.animState * 0.8f), 8f * t.animState, Offset(0f, -94f - recoil))
                    }
                }

                // FREEZE: cryo spire with snowflake arms
                TowerType.FREEZE -> {
                    val recoil = t.animState * 10f
                    // central spire
                    drawLine(Color(0xFF040D18), Offset(-6f, -86f - recoil), Offset(-6f, 0f), 1f)
                    drawRect(Color(0xFF040D18), Offset(-7f, -82f - recoil), Size(14f, 84f))
                    drawRect(t.type.color.copy(0.6f), Offset(-7f, -82f - recoil), Size(14f, 84f), style = Stroke(1.5f))
                    // snowflake arms at various heights
                    listOf(-68f, -44f, -20f).forEach { h ->
                        val arm = 18f - recoil * 0.3f
                        drawLine(t.type.color.copy(0.65f), Offset(-arm, h - recoil), Offset(arm, h - recoil), 2f)
                        drawLine(t.type.color.copy(0.65f), Offset(-arm * 0.7f, h - recoil - arm * 0.7f), Offset(arm * 0.7f, h - recoil + arm * 0.7f), 2f)
                    }
                    // cryo crystal tip
                    drawPath(ngon(0f, -84f - recoil, 10f, 6, 0f), t.type.color.copy(0.85f))
                    drawCircle(Color.White.copy(0.8f + t.animState * 0.2f), 5f + t.animState * 3f, Offset(0f, -84f - recoil))
                }
            }
        }
    }
}

fun DrawScope.drawProjectile(p: Projectile) {
    val center = Offset(p.x, p.y)
    // tail glow
    p.trail.forEachIndexed { i, o ->
        val a = (1f - i.toFloat() / p.trail.size)
        val r = when (p.source) {
            TowerType.CANNON -> 9f - i * 0.8f
            TowerType.NOVA   -> 11f - i * 0.9f
            else             -> 7f - i * 0.6f
        }
        drawCircle(p.color.copy(a * 0.5f), r.coerceAtLeast(1f), o)
    }

    when (p.source) {
        TowerType.TESLA -> {
            // small bright electric orb
            drawCircle(p.color.copy(0.4f), 20f, center)
            drawCircle(p.color, 7f, center)
            drawCircle(Color.White.copy(0.95f), 3f, center)
        }
        TowerType.NOVA -> {
            // large plasma orb with ring
            drawCircle(Brush.radialGradient(listOf(p.color.copy(0.35f), Color.Transparent), center, 60f), 60f, center)
            drawCircle(p.color.copy(0.8f), 12f, center)
            drawCircle(p.color.copy(0.4f), 24f, center, style = Stroke(3f))
            drawCircle(Color.White.copy(0.9f), 5f, center)
        }
        TowerType.CANNON -> {
            // solid dark shell
            drawCircle(Color(0xFF1A0F00), 11f, center)
            drawCircle(p.color, 9f, center)
            drawCircle(Color.White.copy(0.6f), 3f, center)
        }
        TowerType.FREEZE -> {
            // ice shard — hexagon
            drawPath(ngon(p.x, p.y, 14f, 6, 0f), p.color.copy(0.75f))
            drawPath(ngon(p.x, p.y, 14f, 6, 0f), Color.White.copy(0.5f), style = Stroke(2f))
            drawCircle(Color.White.copy(0.9f), 4f, center)
        }
    }
}

fun DrawScope.drawParticle(p: Particle) {
    val a = p.life.coerceIn(0f, 1f)
    when (p.type) {
        ParticleType.SMOKE -> {
            drawCircle(p.color.copy(a * 0.18f), p.size * (1.8f - a * 0.6f), Offset(p.x, p.y))
        }
        ParticleType.RING -> {
            drawCircle(p.color.copy(a * 0.8f), p.size, Offset(p.x, p.y), style = Stroke(2f))
        }
        ParticleType.SPARK -> {
            // elongated streak
            val len = p.size * a * 1.8f
            val speed = sqrt(p.vx * p.vx + p.vy * p.vy).coerceAtLeast(0.01f)
            drawLine(
                p.color.copy(a * 0.85f),
                Offset(p.x - p.vx / speed * len * 0.5f, p.y - p.vy / speed * len * 0.5f),
                Offset(p.x + p.vx / speed * len * 0.5f, p.y + p.vy / speed * len * 0.5f),
                (p.size * a * 0.55f).coerceAtLeast(1f),
                cap = StrokeCap.Round
            )
            drawCircle(Color.White.copy(a * 0.7f), p.size * 0.28f * a, Offset(p.x, p.y))
        }
    }
}

// ═══════════════════════════════════════════════
//  COMPOSABLES
// ═══════════════════════════════════════════════

@Composable
fun GameScreen() {
    val engine = remember { GameEngine() }
    var screenSize  by remember { mutableStateOf(Size.Zero) }
    var selType     by remember { mutableStateOf(TowerType.TESLA) }
    var upgradeMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        var last = System.currentTimeMillis()
        while (isActive) {
            val now = System.currentTimeMillis()
            val dt = ((now - last) / 1000f).coerceAtMost(0.05f)
            last = now
            if (screenSize.width > 0) engine.update(dt)
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF05050F))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(upgradeMode, selType) {
                detectTapGestures { tap ->
                    if (engine.state == GameState.GAME_OVER) return@detectTapGestures
                    if (upgradeMode) engine.upgrade(tap.x, tap.y)
                    else engine.build(tap.x, tap.y, selType)
                }
            }
        ) {
            screenSize = this.size
            engine.init(size.width, size.height)

            val sx = (Random.nextFloat() - 0.5f) * engine.screenShake
            val sy = (Random.nextFloat() - 0.5f) * engine.screenShake

            withTransform({ translate(sx, sy) }) {
                drawBackground(engine.path, engine.globalTime)

                // Projectile halos
                engine.projectiles.forEach { p ->
                    drawCircle(
                        Brush.radialGradient(listOf(p.color.copy(0.16f), Color.Transparent), Offset(p.x, p.y), 200f),
                        200f, Offset(p.x, p.y)
                    )
                }

                engine.towers.forEach     { drawTower(it, engine.globalTime) }
                engine.enemies.forEach    { drawEnemy(it, engine.globalTime) }
                engine.projectiles.forEach{ drawProjectile(it) }
                engine.particles.forEach  { drawParticle(it) }

                // Chromatic aberration
                if (engine.chromatic > 1.5f) {
                    val ca = engine.chromatic / 22f
                    drawRect(Color.Red.copy(ca * 0.12f),  blendMode = BlendMode.Plus)
                    drawRect(Color.Blue.copy(ca * 0.10f), blendMode = BlendMode.Screen)
                }
            }
        }

        GameUI(engine, selType, upgradeMode,
            onSelect = { selType = it; upgradeMode = false },
            onToggleUpgrade = { upgradeMode = !upgradeMode },
            onRestart = { engine.reset() }
        )
    }
}

@Composable
fun GameUI(
    engine: GameEngine,
    sel: TowerType,
    upgradeMode: Boolean,
    onSelect: (TowerType) -> Unit,
    onToggleUpgrade: () -> Unit,
    onRestart: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        TopHUD(engine)
        if (engine.state == GameState.WAVE_BREAK)  WaveBanner(engine)
        if (engine.state != GameState.GAME_OVER)   BottomBar(engine, sel, upgradeMode, onSelect, onToggleUpgrade)
        if (engine.state == GameState.GAME_OVER)   GameOverScreen(engine.score, engine.wave - 1, onRestart)
    }
}

@Composable
fun BoxScope.TopHUD(e: GameEngine) {
    Row(
        Modifier.fillMaxWidth().align(Alignment.TopCenter)
            .padding(10.dp)
            .background(Color(0xDD05050F), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("KINGSHOT", color = Color.White, fontWeight = FontWeight.Black,
                fontSize = 15.sp, letterSpacing = 3.sp)
            Text("WAVE ${e.wave}", color = Color(0xFF00E5FF),
                fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            HudChip("◆", "${e.gold}", Color(0xFFFFD740))
            HudChip("♥", "${e.health}", if (e.health > 5) Color(0xFF69F0AE) else Color(0xFFFF1744))
            HudChip("★", "${e.score}", Color(0xFFFF9100))
        }
    }
}

@Composable
fun HudChip(icon: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(icon, color = color, fontSize = 12.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun BoxScope.WaveBanner(e: GameEngine) {
    Column(
        Modifier.align(Alignment.Center)
            .background(Color(0xCC05050F), RoundedCornerShape(18.dp))
            .padding(horizontal = 32.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (e.wave == 1) "PREPARE FOR BATTLE" else "WAVE ${e.wave - 1} CLEARED!",
            color = Color(0xFFFFD740), fontSize = 22.sp,
            fontWeight = FontWeight.Black, letterSpacing = 2.sp
        )
        Text("WAVE ${e.wave} INCOMING IN ${e.breakTimer.toInt() + 1}s",
            color = Color.White.copy(0.65f), fontSize = 14.sp)
        if (e.wave > 1)
            Text("+${200 + e.wave * 50} CR BONUS",
                color = Color(0xFF69F0AE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BoxScope.BottomBar(
    e: GameEngine, sel: TowerType, upgradeMode: Boolean,
    onSelect: (TowerType) -> Unit, onToggleUpgrade: () -> Unit
) {
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color(0xEE05050F))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TowerType.entries.forEach { t ->
            TowerCard(t, sel == t, e.gold >= t.cost) { onSelect(t) }
        }
        UpgradeButton(upgradeMode, onToggleUpgrade)
    }
}

@Composable
fun TowerCard(type: TowerType, active: Boolean, canBuy: Boolean, onClick: () -> Unit) {
    val bg     = if (active) type.color.copy(0.22f) else Color.White.copy(0.04f)
    val border = if (active) type.color             else Color.White.copy(0.10f)
    Column(
        Modifier
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .background(bg, RoundedCornerShape(12.dp))
            .border(if (active) 2.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(type.label,
            color = if (canBuy) type.color else Color.Gray,
            fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 0.5.sp)
        Text("${type.cost}◆",
            color = if (canBuy) Color.White.copy(0.75f) else Color.Gray.copy(0.4f),
            fontSize = 10.sp)
    }
}

@Composable
fun UpgradeButton(active: Boolean, onClick: () -> Unit) {
    val c = Color(0xFF69F0AE)
    Column(
        Modifier
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .background(if (active) c.copy(0.18f) else Color.White.copy(0.03f), RoundedCornerShape(12.dp))
            .border(if (active) 2.dp else 1.dp,
                    if (active) c else Color.White.copy(0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("▲", color = if (active) c else Color.Gray, fontSize = 14.sp)
        Text("UPGRD", color = if (active) c else Color.Gray,
            fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BoxScope.GameOverScreen(score: Int, waves: Int, onRestart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xF205050F)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SYSTEM DESTROYED", color = Color(0xFFFF1744), fontSize = 26.sp,
            fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Spacer(Modifier.height(16.dp))
        Text("SURVIVED $waves WAVE${if (waves != 1) "S" else ""}",
            color = Color.White.copy(0.6f), fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text("SCORE: $score", color = Color(0xFFFFD740), fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(36.dp))
        Box(
            Modifier
                .pointerInput(Unit) { detectTapGestures { onRestart() } }
                .background(Color(0xFF00E5FF).copy(0.12f), RoundedCornerShape(50.dp))
                .border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(50.dp))
                .padding(horizontal = 44.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("RESTART", color = Color(0xFF00E5FF), fontSize = 15.sp,
                fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        }
    }
}

// ═══════════════════════════════════════════════
//  MAIN ACTIVITY
// ═══════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GameScreen() }
    }
}
