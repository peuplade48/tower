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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
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
//  DRAWING FUNCTIONS (DrawScope extensions)
// ═══════════════════════════════════════════════

fun DrawScope.drawBackground(path: List<PNode>, t: Float) {
    drawRect(Color(0xFF05050F))

    // Grid
    val g = 55f
    val cols = (size.width / g).toInt() + 2
    val rows = (size.height / g).toInt() + 2
    repeat(cols) { c -> drawLine(Color(0xFF0C0C22), Offset(c * g, 0f), Offset(c * g, size.height), 1f) }
    repeat(rows) { r -> drawLine(Color(0xFF0C0C22), Offset(0f, r * g), Offset(size.width, r * g), 1f) }

    if (path.size < 2) return

    // Path glow layers
    for (i in 0 until path.size - 1) {
        val a = Offset(path[i].x, path[i].y)
        val b = Offset(path[i + 1].x, path[i + 1].y)
        drawLine(Color(0x18FF9800), a, b, 88f, cap = StrokeCap.Round)
        drawLine(Color(0x33FF9800), a, b, 40f, cap = StrokeCap.Round)
        drawLine(Color(0x66FF9800), a, b, 16f, cap = StrokeCap.Round)
        drawLine(Color(0xBBFFB300), a, b,  5f, cap = StrokeCap.Round)
    }

    // Animated direction arrows
    repeat(14) { k ->
        val pct = ((t * 0.28f + k.toFloat() / 14f) % 1f)
        val pos = pathPos(path, pct)
        val nxt = pathPos(path, (pct + 0.012f).coerceAtMost(1f))
        val ang = atan2(nxt.y - pos.y, nxt.x - pos.x)
        val al = (0.35f + 0.28f * sin(t * 2.5f + k)).coerceIn(0f, 1f)
        withTransform({ translate(pos.x, pos.y); rotate(ang * 180f / PI.toFloat(), Offset.Zero) }) {
            drawLine(Color(0xFFFF9800).copy(al), Offset(-9f, -7f), Offset(0f, 0f), 2.5f, cap = StrokeCap.Round)
            drawLine(Color(0xFFFF9800).copy(al), Offset(-9f,  7f), Offset(0f, 0f), 2.5f, cap = StrokeCap.Round)
        }
    }
}

fun DrawScope.drawEnemy(e: Enemy, t: Float) {
    val s = e.type.scale
    val bob = sin(t * 3.5f + e.id.toFloat()) * 7f
    val baseC = if (e.hitFlash > 0.25f) Color.White else e.type.color
    val hpR = (e.hp.toFloat() / e.type.hp).coerceIn(0f, 1f)

    withTransform({ translate(e.x, e.y + bob) }) {
        when (e.type) {
            EnemyType.VOID_WALKER -> {
                drawCircle(e.type.color.copy(0.13f), 65f * s)
                withTransform({ rotate(t * 85f, Offset.Zero) }) {
                    drawCircle(e.type.color.copy(0.5f), 50f * s, style = Stroke(3f))
                }
                drawCircle(baseC, 36f * s)
                drawCircle(Color.Black.copy(0.45f), 21f * s)
                drawCircle(Color.White.copy(0.85f), 9f * s * (0.8f + sin(t * 9f) * 0.2f))
            }
            EnemyType.SPECTER -> {
                drawCircle(e.type.color.copy(0.12f), 55f * s)
                withTransform({ rotate(45f + t * 65f, Offset.Zero) }) {
                    drawRect(e.type.color.copy(0.3f), Offset(-40f * s, -40f * s), Size(80f * s, 80f * s), style = Stroke(2.5f))
                }
                withTransform({ rotate(45f, Offset.Zero) }) {
                    drawRect(baseC, Offset(-27f * s, -27f * s), Size(54f * s, 54f * s))
                }
                drawCircle(Color.White.copy(0.9f), 7f * s)
            }
            EnemyType.IRON_GOLEM -> {
                drawCircle(e.type.color.copy(0.10f), 112f * s)
                drawCircle(Color(0xFF1C2B30), 72f * s)
                drawCircle(e.type.color.copy(0.65f), 72f * s, style = Stroke(12f))
                withTransform({ rotate(t * 22f, Offset.Zero) }) {
                    drawCircle(e.type.color.copy(0.25f), 56f * s, style = Stroke(4f))
                }
                drawCircle(baseC.copy(0.9f), 33f * s)
                drawCircle(Color.White.copy(0.45f), 11f * s)
            }
            EnemyType.DRAGON_BORN -> {
                drawCircle(e.type.color.copy(0.06f), 210f * s)
                drawCircle(e.type.color.copy(0.13f), 170f * s)
                withTransform({ rotate(t * 28f, Offset.Zero) }) {
                    drawCircle(e.type.color.copy(0.45f), 135f * s, style = Stroke(6f))
                    drawCircle(e.type.color.copy(0.22f), 110f * s, style = Stroke(3f))
                }
                withTransform({ rotate(-t * 55f, Offset.Zero) }) {
                    drawCircle(Color.White.copy(0.25f), 90f * s, style = Stroke(4f))
                }
                drawCircle(baseC, 78f * s)
                drawCircle(Color.Black.copy(0.5f), 48f * s)
                drawCircle(e.type.color, 24f * s * (0.85f + sin(t * 7f) * 0.15f))
                drawCircle(Color.White.copy(0.9f), 10f * s)
            }
        }

        if (e.frozen > 0) drawCircle(Color(0x5540C4FF), 60f * s)

        // HP bar
        val bw = 84f * s; val bh = 7f; val barY = -110f * s
        drawRoundRect(Color.Black.copy(0.75f), Offset(-bw / 2f, barY), Size(bw, bh), 4f, 4f)
        if (hpR > 0f) drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFFFF1744), Color(0xFFFFAB00), Color(0xFF69F0AE))),
            Offset(-bw / 2f, barY), Size(bw * hpR, bh), 4f, 4f
        )
    }
}

fun DrawScope.drawTower(t: Tower, time: Float) {
    withTransform({ translate(t.x, t.y) }) {
        val p = 1f + sin(time * 4.5f) * 0.07f
        val lc = when (t.level) { 2 -> Color(0xFFFFD740); 3 -> Color(0xFFFF6E40); else -> t.type.color }

        drawCircle(t.type.color.copy(0.10f * p), 92f)
        drawCircle(lc.copy(0.38f), 74f, style = Stroke(2f + t.level))
        drawCircle(Color(0xFF10102A), 62f)
        drawCircle(Color(0xFF1A1A3A), 55f)
        drawCircle(t.type.color.copy(0.38f), 52f, style = Stroke(7f))

        // Level dots
        for (i in 0 until t.level) {
            val a = (i / 3f) * 2 * PI.toFloat() - PI.toFloat() / 2
            drawCircle(lc, 5f, Offset(cos(a).toFloat() * 43f, sin(a).toFloat() * 43f))
        }

        withTransform({ rotate(t.angle, Offset.Zero) }) {
            when (t.type) {
                TowerType.TESLA -> {
                    val yo = -82f - t.animState * 16f
                    drawRect(Color(0xFF071525), Offset(-11f, yo), Size(22f, 92f))
                    drawRect(t.type.color.copy(0.8f), Offset(-11f, yo), Size(22f, 92f), style = Stroke(2f))
                    drawCircle(t.type.color, 9f, Offset(0f, yo + 12f), alpha = 0.7f + t.animState * 0.3f)
                    drawCircle(Color.White.copy(0.9f + t.animState * 0.1f), 4f + t.animState * 5f, Offset(0f, yo))
                }
                TowerType.NOVA -> {
                    val yo = -72f - t.animState * 18f
                    drawRect(Color(0xFF1A0507), Offset(-17f, yo), Size(34f, 82f))
                    drawRect(t.type.color.copy(0.8f), Offset(-17f, yo), Size(34f, 82f), style = Stroke(3f))
                    drawCircle(t.type.color, 13f, Offset(0f, yo + 14f), alpha = 0.7f + t.animState * 0.3f)
                    if (t.animState > 0.3f) drawCircle(Color.White.copy(t.animState * 0.8f), 9f, Offset(0f, yo + 14f))
                }
                TowerType.CANNON -> {
                    val yo = -88f - t.animState * 20f
                    drawRect(Color(0xFF160900), Offset(-13f, yo), Size(26f, 98f))
                    drawRect(t.type.color.copy(0.8f), Offset(-13f, yo), Size(26f, 98f), style = Stroke(3f))
                    for (i in 0..2) drawRect(t.type.color.copy(0.45f), Offset(-15f, yo + 15f + i * 22f), Size(30f, 6f))
                    drawCircle(t.type.color, 10f, Offset(0f, yo + 10f), alpha = 0.6f + t.animState * 0.4f)
                }
                TowerType.FREEZE -> {
                    val yo = -78f - t.animState * 12f
                    drawRect(Color(0xFF040D18), Offset(-10f, yo), Size(20f, 88f))
                    drawRect(t.type.color.copy(0.8f), Offset(-10f, yo), Size(20f, 88f), style = Stroke(2f))
                    drawCircle(t.type.color, 11f, Offset(0f, yo + 12f), alpha = 0.8f + t.animState * 0.2f)
                    drawCircle(Color.White.copy(0.65f), 6f, Offset(0f, yo + 12f))
                }
            }
        }
    }
}

fun DrawScope.drawProjectile(p: Projectile) {
    p.trail.forEachIndexed { i, o ->
        val a = (1f - i.toFloat() / p.trail.size) * 0.55f
        drawCircle(p.color.copy(a), (14f - i * 1.3f).coerceAtLeast(1f), o)
    }
    drawCircle(Color.White, 8f, Offset(p.x, p.y))
    drawCircle(p.color.copy(0.55f), 22f, Offset(p.x, p.y), style = Stroke(3f))
    drawCircle(
        Brush.radialGradient(listOf(p.color.copy(0.28f), Color.Transparent), Offset(p.x, p.y), 80f),
        80f, Offset(p.x, p.y)
    )
}

fun DrawScope.drawParticle(p: Particle) {
    val a = p.life.coerceIn(0f, 1f)
    when (p.type) {
        ParticleType.SMOKE -> drawCircle(p.color.copy(a * 0.2f), p.size * (2f - a), Offset(p.x, p.y))
        ParticleType.RING  -> drawCircle(p.color.copy(a), p.size, Offset(p.x, p.y), style = Stroke(2f))
        ParticleType.SPARK -> {
            drawCircle(p.color.copy(a), p.size * a, Offset(p.x, p.y))
            drawCircle(Color.White.copy(a * 0.75f), p.size * 0.35f * a, Offset(p.x, p.y))
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
