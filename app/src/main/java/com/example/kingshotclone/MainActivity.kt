package com.example.kingshotclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.*
import kotlin.random.Random

// --- GÖRSEL EFEKT MODELLERİ ---

data class VisualParticle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float, val color: Color,
    val size: Float,
    val trail: MutableList<Offset> = mutableListOf()
)

data class DamageText(
    val x: Float, var y: Float,
    val text: String, var life: Float = 1f
)

// --- OYUN BİRİMLERİ ---

enum class EnemyType(val color: Color, val hp: Int, val speed: Float, val scale: Float) {
    GHOST(Color(0xFF81D4FA), 80, 3f, 1f),
    ORC(Color(0xFF4CAF50), 250, 2f, 1.3f),
    BOSS(Color(0xFFE91E63), 3000, 1f, 2.5f)
}

data class Enemy(
    var x: Float, var y: Float,
    val type: EnemyType,
    var currentHp: Int,
    var id: Long = Random.nextLong(),
    var phase: Float = Random.nextFloat() * 10f
)

enum class TowerType(val color: Color, val cost: Int, val range: Float, val dmg: Int) {
    PLASMA(Color(0xFF7C4DFF), 200, 700f, 40),
    HELLFIRE(Color(0xFFFF5252), 500, 500f, 150)
}

data class Tower(
    val x: Float, val y: Float,
    val type: TowerType,
    var level: Int = 1,
    var angle: Float = 0f,
    var heat: Float = 0f // Ateş ettikçe parlaması için
)

data class Projectile(
    var x: Float, var y: Float,
    val targetId: Long,
    val color: Color,
    val dmg: Int
)

// --- AAA OYUN MOTORU ---

class AAAGameEngine {
    var enemies = mutableStateListOf<Enemy>()
    var towers = mutableStateListOf<Tower>()
    var projectiles = mutableStateListOf<Projectile>()
    var particles = mutableStateListOf<VisualParticle>()
    var damageTexts = mutableStateListOf<DamageText>()
    
    var gold by mutableIntStateOf(1000)
    var health by mutableIntStateOf(100)
    var wave by mutableIntStateOf(1)
    var screenShake by mutableFloatStateOf(0f)
    var time = 0f

    fun update(width: Float, height: Float) {
        if (health <= 0) return
        time += 0.02f
        if (screenShake > 0) screenShake *= 0.9f

        // Düşman Üretimi
        if (Random.nextInt(100) < 3 + (wave / 2)) {
            val type = if(Random.nextFloat() < 0.1f && wave > 3) EnemyType.BOSS else if(Random.nextFloat() < 0.3f) EnemyType.ORC else EnemyType.GHOST
            enemies.add(Enemy(Random.nextFloat() * width, -100f, type, type.hp))
        }

        // Parçacık Fiziği
        val pIter = particles.listIterator()
        while(pIter.hasNext()) {
            val p = pIter.next()
            p.trail.add(0, Offset(p.x, p.y))
            if(p.trail.size > 5) p.trail.removeLast()
            p.x += p.vx; p.y += p.vy; p.vy += 0.2f // Yerçekimi
            p.life -= 0.02f
            if(p.life <= 0) pIter.remove()
        }

        // Hasar Yazıları
        damageTexts.forEach { it.y -= 2f; it.life -= 0.02f }
        damageTexts.removeAll { it.life <= 0 }

        // Düşman Hareket
        val eIter = enemies.listIterator()
        while(eIter.hasNext()) {
            val e = eIter.next()
            e.y += e.type.speed
            // Obaya saldırı
            if (e.y > height - 300f) {
                health -= if(e.type == EnemyType.BOSS) 50 else 10
                screenShake = 50f
                spawnExplosion(e.x, e.y, Color.Red, 40)
                eIter.remove()
            }
        }

        // Kule Mantığı
        towers.forEach { t ->
            if (t.heat > 0) t.heat *= 0.9f
            val target = enemies.filter { sqrt((it.x-t.x).pow(2)+(it.y-t.y).pow(2)) < t.type.range }
                                .minByOrNull { it.y }
            
            if (target != null) {
                val dx = target.x - t.x
                val dy = target.y - t.y
                t.angle = atan2(dy, dx) * (180/PI).toFloat() + 90f
                
                if (Random.nextInt(100) < 5 * t.level) {
                    projectiles.add(Projectile(t.x, t.y, target.id, t.type.color, t.type.dmg))
                    t.heat = 1f
                }
            }
        }

        // Mermiler
        val prIter = projectiles.listIterator()
        while(prIter.hasNext()) {
            val p = prIter.next()
            val target = enemies.find { it.id == p.targetId }
            if (target == null) { prIter.remove(); continue }

            val dx = target.x - p.x
            val dy = target.y - p.y
            val dist = sqrt(dx*dx + dy*dy)
            
            p.x += (dx/dist) * 25f
            p.y += (dy/dist) * 25f

            if (dist < 50f) {
                target.currentHp -= p.dmg
                damageTexts.add(DamageText(p.x, p.y, "-${p.dmg}"))
                spawnExplosion(p.x, p.y, p.color, 10)
                
                if (target.currentHp <= 0) {
                    gold += if(target.type == EnemyType.BOSS) 1000 else 50
                    spawnExplosion(target.x, target.y, target.type.color, 30)
                    enemies.remove(target)
                }
                prIter.remove()
            }
        }
    }

    private fun spawnExplosion(x: Float, y: Float, color: Color, count: Int) {
        repeat(count) {
            particles.add(VisualParticle(x, y, Random.nextFloat()*16-8, Random.nextFloat()*16-8, 1f, color, 8f))
        }
    }

    fun build(x: Float, y: Float, type: TowerType) {
        if (gold >= type.cost && y < 1400f) {
            towers.add(Tower(x, y, type))
            gold -= type.cost
        }
    }
}

// --- GÖRSEL KATMAN (UI & CANVAS) ---

@Composable
fun AAAGame() {
    val engine = remember { AAAGameEngine() }
    var size by remember { mutableStateOf(Offset.Zero) }
    var selectedType by remember { mutableStateOf(TowerType.PLASMA) }

    LaunchedEffect(Unit) {
        while(isActive) {
            if (size.x > 0) engine.update(size.x, size.y)
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF020205))) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { engine.build(it.x, it.y, selectedType) }
        }) {
            size = Offset(this.size.width, this.size.height)
            
            val shake = Offset((Random.nextFloat()-0.5f)*engine.screenShake, (Random.nextFloat()-0.5f)*engine.screenShake)

            translate(shake.x, shake.y) {
                // 1. Derinlik (Vignette & Gradient Ground)
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0A1A), Color(0xFF020205))))
                
                // Izgara Efekti (Neon Grid)
                for(i in 0..15) {
                    val y = (i * (size.y/15))
                    drawLine(Color.White.copy(0.05f), Offset(0f, y), Offset(size.x, y), 2f)
                }

                // 2. Kuleler (AAA Mekanik Detaylar)
                engine.towers.forEach { t ->
                    drawTowerAAA(this, t, engine.time)
                }

                // 3. Düşmanlar (Gölge ve Glow ile)
                engine.enemies.forEach { e ->
                    drawEnemyAAA(this, e, engine.time)
                }

                // 4. Parçacıklar (VFX Trail)
                engine.particles.forEach { p ->
                    p.trail.forEachIndexed { index, offset ->
                        drawCircle(p.color.copy(alpha = p.life * (1f - index/5f)), p.size * (1f - index/5f), offset)
                    }
                    drawCircle(Color.White.copy(p.life), p.size * 0.5f, Offset(p.x, p.y))
                }

                // 5. Mermiler (Işık İzleri)
                engine.projectiles.forEach { p ->
                    drawCircle(p.color.copy(0.3f), 40f, Offset(p.x, p.y))
                    drawCircle(p.color, 12f, Offset(p.x, p.y))
                    drawCircle(Color.White, 6f, Offset(p.x, p.y))
                }
                
                // 6. Hasar Yazıları
                engine.damageTexts.forEach { dt ->
                    // Canvas drawText karmaşık olduğu için basit görsel yapıyoruz
                    drawCircle(Color.White.copy(dt.life), 5f, Offset(dt.x, dt.y))
                }
            }
        }

        // --- ÜST PANEL (Glassmorphism) ---
        GameOverlay(engine, selectedType) { selectedType = it }
    }
}

fun drawTowerAAA(scope: DrawScope, t: Tower, time: Float) {
    scope.translate(t.x, t.y) {
        // Alt Taban (Gölge)
        scope.drawCircle(Color.Black.copy(0.5f), 70f, Offset(5f, 5f))
        scope.drawCircle(Color.DarkGray, 65f)
        
        // Dönen Halkalar
        scope.rotate(time * 100f) {
            scope.drawCircle(t.type.color.copy(0.4f), 80f, style = Stroke(4f))
            scope.drawRect(t.type.color, Offset(-10f, -85f), Size(20f, 10f))
        }

        // Kule Gövdesi
        scope.rotate(t.angle) {
            val towerColor = t.type.color
            // Namlu
            scope.drawRect(towerColor, Offset(-20f, -100f), Size(40f, 80f))
            // Namlu Ucu (Heat Glow)
            if (t.heat > 0.1f) {
                scope.drawRect(Color.White.copy(t.heat), Offset(-20f, -105f), Size(40f, 10f))
            }
        }
    }
}

fun drawEnemyAAA(scope: DrawScope, e: Enemy, time: Float) {
    val s = e.type.scale
    val bobbing = sin(time * 5f + e.phase) * 10f
    
    scope.translate(e.x, e.y + bobbing) {
        // Yer Gölgesi
        scope.drawOval(Color.Black.copy(0.3f), Offset(-40f*s, 60f*s), Size(80f*s, 30f*s))
        
        // Gövde (Bloom Etkisi)
        scope.drawCircle(e.type.color.copy(0.2f), 60f * s)
        scope.drawCircle(e.type.color, 45f * s)
        
        // Gözler (Parlama)
        val eyeColor = if(e.type == EnemyType.BOSS) Color.White else Color.Red
        scope.drawCircle(eyeColor, 8f * s, Offset(-15f*s, -10f*s))
        scope.drawCircle(eyeColor, 8f * s, Offset(15f*s, -10f*s))
        
        // HP Bar (Sıvı Efekti)
        val hpWidth = 100f * s
        val currentHpWidth = hpWidth * (e.currentHp.toFloat() / e.type.hp)
        scope.drawRect(Color.Black.copy(0.5f), Offset(-hpWidth/2, -80f*s), Size(hpWidth, 10f))
        scope.drawRect(
            Brush.horizontalGradient(listOf(Color.Red, Color.Magenta)),
            Offset(-hpWidth/2, -80f*s), Size(currentHpWidth, 10f)
        )
    }
}

@Composable
fun GameOverlay(engine: AAAGameEngine, selected: TowerType, onSelect: (TowerType) -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp)) {
        // Skor ve Kaynaklar
        Row(Modifier.fillMaxWidth().background(Color.White.copy(0.05f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(15.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("KINGSOT: OVERDRIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text("SEKTÖR ${engine.wave}", color = Color.Cyan)
            }
            Row {
                StatView("💎 ${engine.gold}", Color(0xFF00E5FF))
                Spacer(Modifier.width(20.dp))
                StatView("🔋 %${engine.health}", if(engine.health > 25) Color.Green else Color.Red)
            }
        }

        // Kule Seçimi (Bottom)
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp).background(Color.Black.copy(0.7f), androidx.compose.foundation.shape.RoundedCornerShape(30.dp)).padding(10.dp)) {
            TowerSlot("PLASMA", TowerType.PLASMA, selected == TowerType.PLASMA) { onSelect(TowerType.PLASMA) }
            TowerSlot("HELLFIRE", TowerType.HELLFIRE, selected == TowerType.HELLFIRE) { onSelect(TowerType.HELLFIRE) }
        }
    }
    
    if(engine.health <= 0) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.9f)), contentAlignment = Alignment.Center) {
            Text("SİSTEM ÇÖKTÜ", color = Color.Red, fontSize = 40.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun TowerSlot(name: String, type: TowerType, isSelected: Boolean, onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 10.dp).pointerInput(Unit) { detectTapGestures { onClick() } }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(70.dp).background(if(isSelected) type.color else Color.Gray.copy(0.2f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Text("${type.cost}", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
fun StatView(label: String, color: Color) {
    Text(label, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AAAGame() }
    }
}
