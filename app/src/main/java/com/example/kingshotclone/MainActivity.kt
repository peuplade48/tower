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

// --- ULTRA GÖRSEL MODELLER ---

data class UltraParticle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float, val color: Color,
    val size: Float,
    val type: ParticleType = ParticleType.SPARK
)

enum class ParticleType { SPARK, SMOKE, DEBRIS }

data class LightSource(
    val x: Float, val y: Float,
    val color: Color,
    val radius: Float,
    val intensity: Float
)

// --- OYUN BİRİMLERİ ---

enum class EnemyType(val color: Color, val hp: Int, val speed: Float, val scale: Float) {
    VOID_WALKER(Color(0xFFBB86FC), 120, 2.8f, 1.1f),
    IRON_GOLEM(Color(0xFFCFD8DC), 500, 1.2f, 1.6f),
    DRAGON_BORN(Color(0xFFFFD600), 5000, 0.8f, 3.2f)
}

data class Enemy(
    var x: Float, var y: Float,
    val type: EnemyType,
    var currentHp: Int,
    var id: Long = Random.nextLong(),
    var hitFlash: Float = 0f // Darbe aldığında parlama süresi
)

enum class TowerType(val color: Color, val cost: Int, val range: Float, val dmg: Int, val fireRate: Long) {
    TESLA(Color(0xFF00E5FF), 250, 750f, 35, 400),
    NOVA(Color(0xFFFF1744), 600, 500f, 200, 1200)
}

data class Tower(
    val x: Float, val y: Float,
    val type: TowerType,
    var angle: Float = 0f,
    var lastShot: Long = 0,
    var animState: Float = 0f
)

data class Projectile(
    var x: Float, var y: Float,
    val targetId: Long,
    val color: Color,
    val dmg: Int,
    val trail: MutableList<Offset> = mutableListOf()
)

// --- ULTRA OYUN MOTORU ---

class UltraGameEngine {
    var enemies = mutableStateListOf<Enemy>()
    var towers = mutableStateListOf<Tower>()
    var projectiles = mutableStateListOf<Projectile>()
    var particles = mutableStateListOf<UltraParticle>()
    
    var gold by mutableIntStateOf(1500)
    var health by mutableIntStateOf(100)
    var wave by mutableIntStateOf(1)
    var screenShake by mutableFloatStateOf(0f)
    var chromaticAberration by mutableFloatStateOf(0f)
    var globalTime = 0f

    fun update(width: Float, height: Float) {
        if (health <= 0) return
        globalTime += 0.02f
        if (screenShake > 0) screenShake *= 0.85f
        if (chromaticAberration > 0) chromaticAberration *= 0.9f

        // Düşman Spawn
        if (Random.nextInt(100) < 4 + (wave / 3)) {
            val type = when {
                wave > 5 && Random.nextFloat() < 0.05f -> EnemyType.DRAGON_BORN
                Random.nextFloat() < 0.25f -> EnemyType.IRON_GOLEM
                else -> EnemyType.VOID_WALKER
            }
            enemies.add(Enemy(Random.nextFloat() * width, -150f, type, type.hp))
        }

        // Parçacık Sistemi
        val pIter = particles.listIterator()
        while(pIter.hasNext()) {
            val p = pIter.next()
            p.x += p.vx; p.y += p.vy
            if (p.type == ParticleType.SPARK) p.vy += 0.3f // Gravity
            p.life -= 0.015f
            if(p.life <= 0) pIter.remove()
        }

        // Düşmanlar
        val eIter = enemies.listIterator()
        while(eIter.hasNext()) {
            val e = eIter.next()
            e.y += e.type.speed
            if (e.hitFlash > 0) e.hitFlash -= 0.1f
            
            if (e.y > height - 300f) {
                health -= if(e.type == EnemyType.DRAGON_BORN) 60 else 15
                screenShake = 45f
                chromaticAberration = 15f
                spawnExplosion(e.x, e.y, Color.Red, 50)
                eIter.remove()
            }
        }

        // Kuleler & Atışlar
        val now = System.currentTimeMillis()
        towers.forEach { t ->
            val target = enemies.filter { sqrt((it.x-t.x).pow(2)+(it.y-t.y).pow(2)) < t.type.range }
                                .minByOrNull { it.y }
            
            if (target != null) {
                t.angle = atan2(target.y - t.y, target.x - t.x) * (180/PI).toFloat() + 90f
                if (now - t.lastShot > t.type.fireRate) {
                    projectiles.add(Projectile(t.x, t.y, target.id, t.type.color, t.type.dmg))
                    t.lastShot = now
                    t.animState = 1.0f
                }
            }
            if (t.animState > 0) t.animState -= 0.05f
        }

        // Mermiler
        val prIter = projectiles.listIterator()
        while(prIter.hasNext()) {
            val p = prIter.next()
            val target = enemies.find { it.id == p.targetId }
            if (target == null) { prIter.remove(); continue }

            val dx = target.x - p.x; val dy = target.y - p.y
            val dist = sqrt(dx*dx + dy*dy)
            
            p.trail.add(0, Offset(p.x, p.y))
            if(p.trail.size > 8) p.trail.removeLast()

            p.x += (dx/dist) * 35f
            p.y += (dy/dist) * 35f

            if (dist < 60f) {
                target.currentHp -= p.dmg
                target.hitFlash = 1.0f
                spawnExplosion(p.x, p.y, p.color, 12)
                
                if (target.currentHp <= 0) {
                    gold += if(target.type == EnemyType.DRAGON_BORN) 2000 else 75
                    if(gold > wave * 3000) wave++
                    spawnExplosion(target.x, target.y, target.type.color, 40)
                    enemies.remove(target)
                }
                prIter.remove()
            }
        }
    }

    private fun spawnExplosion(x: Float, y: Float, color: Color, count: Int) {
        repeat(count) {
            particles.add(UltraParticle(x, y, Random.nextFloat()*20-10, Random.nextFloat()*20-10, 1f, color, 10f))
            if(Random.nextFloat() > 0.7f) {
                particles.add(UltraParticle(x, y, Random.nextFloat()*4-2, Random.nextFloat()*4-2, 1.5f, Color.Gray, 25f, ParticleType.SMOKE))
            }
        }
    }

    fun build(x: Float, y: Float, type: TowerType) {
        if (gold >= type.cost && y < 1500f) {
            towers.add(Tower(x, y, type))
            gold -= type.cost
            spawnExplosion(x, y, type.color, 30)
        }
    }
}

// --- ULTRA GÖRSEL ÇİZİM SİSTEMİ ---

@Composable
fun UltraGame() {
    val engine = remember { UltraGameEngine() }
    var size by remember { mutableStateOf(Offset.Zero) }
    var selectedType by remember { mutableStateOf(TowerType.TESLA) }

    LaunchedEffect(Unit) {
        while(isActive) {
            if (size.x > 0) engine.update(size.x, size.y)
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF010103))) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { engine.build(it.x, it.y, selectedType) }
        }) {
            size = Offset(this.size.width, this.size.height)
            
            // Post-Processing: Screen Shake & Chromatic Aberration
            val shake = Offset((Random.nextFloat()-0.5f)*engine.screenShake, (Random.nextFloat()-0.5f)*engine.screenShake)

            translate(shake.x, shake.y) {
                // 1. ZEMİN & SİS (Atmospheric Layers)
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0A1F), Color(0xFF010103))))
                
                // Dinamik Sis Katmanı
                for(i in 0..5) {
                    val fogX = (sin(engine.globalTime * 0.5f + i) * 100f)
                    drawCircle(
                        Color.White.copy(0.03f), 
                        radius = 400f, 
                        center = Offset(fogX + (i * size.x/4), size.y - 200f)
                    )
                }

                // 2. IŞIK KAYNAKLARI (Point Lights Mask)
                // Her mermi ve kule bir ışık kaynağıdır
                engine.projectiles.forEach { p ->
                    drawCircle(
                        Brush.radialGradient(listOf(p.color.copy(0.2f), Color.Transparent), center = Offset(p.x, p.y), radius = 150f),
                        radius = 150f, center = Offset(p.x, p.y)
                    )
                }

                // 3. KULELER
                engine.towers.forEach { t -> drawUltraTower(this, t, engine.globalTime) }

                // 4. DÜŞMANLAR
                engine.enemies.forEach { e -> drawUltraEnemy(this, e, engine.globalTime) }

                // 5. MERMİLER (High-End Trails)
                engine.projectiles.forEach { p ->
                    p.trail.forEachIndexed { index, offset ->
                        val alpha = (1f - index.toFloat()/p.trail.size) * 0.6f
                        drawCircle(p.color.copy(alpha), 15f - index, offset)
                    }
                    drawCircle(Color.White, 10f, Offset(p.x, p.y))
                    drawCircle(p.color.copy(0.5f), 25f, Offset(p.x, p.y), style = Stroke(4f))
                }

                // 6. PARÇACIKLAR (VFX)
                engine.particles.forEach { p ->
                    val alpha = p.life.coerceIn(0f, 1f)
                    if (p.type == ParticleType.SMOKE) {
                        drawCircle(p.color.copy(alpha * 0.2f), p.size * (2f - alpha), Offset(p.x, p.y))
                    } else {
                        drawCircle(p.color.copy(alpha), p.size * alpha, Offset(p.x, p.y))
                        drawCircle(Color.White.copy(alpha), p.size * 0.4f * alpha, Offset(p.x, p.y))
                    }
                }

                // 7. CHROMATIC ABERRATION (Kırmızı/Mavi Kayma Efekti)
                if (engine.chromaticAberration > 1f) {
                    drawRect(Color.Red.copy(0.1f * (engine.chromaticAberration/15f)), blendMode = BlendMode.Plus)
                    drawRect(Color.Blue.copy(0.1f * (engine.chromaticAberration/15f)), blendMode = BlendMode.Screen)
                }
            }
        }

        // --- ULTRA UI KATMANI ---
        UltraUI(engine, selectedType) { selectedType = it }
    }
}

fun drawUltraTower(scope: DrawScope, t: Tower, time: Float) {
    scope.translate(t.x, t.y) {
        // Enerji Halkası (Pulse)
        val pulse = 1f + sin(time * 4f) * 0.1f
        scope.drawCircle(t.type.color.copy(0.1f), 100f * pulse)
        scope.drawCircle(t.type.color.copy(0.3f), 80f * pulse, style = Stroke(2f))

        // Gövde (Metalik Görünüm)
        scope.drawCircle(Color(0xFF1A1A1A), 60f)
        scope.drawCircle(t.type.color.copy(0.5f), 55f, style = Stroke(8f))

        // Üst Kısım (Animasyonlu)
        scope.rotate(t.angle) {
            // Silah Gövdesi
            scope.drawRect(Color(0xFF2C2C2C), Offset(-25f, -90f - (t.animState * 20f)), Size(50f, 100f))
            // Enerji Çekirdeği
            scope.drawCircle(t.type.color, 15f, Offset(0f, -70f), alpha = 0.8f + (t.animState * 0.2f))
        }
    }
}

fun drawUltraEnemy(scope: DrawScope, e: Enemy, time: Float) {
    val s = e.type.scale
    val wave = sin(time * 3f + e.id.toFloat()) * 15f
    
    scope.translate(e.x, e.y + wave) {
        // Bloom/Glow Efekti
        scope.drawCircle(e.type.color.copy(0.2f), 70f * s)
        
        // Ana Gövde (Hit-Flash Efekti Uygulanmış)
        val bodyColor = if (e.hitFlash > 0.1f) Color.White else e.type.color
        scope.drawCircle(bodyColor, 50f * s)
        
        // İç Detay (Enerji Çekirdeği)
        scope.drawCircle(Color.Black.copy(0.4f), 30f * s)
        scope.drawCircle(Color.White.copy(0.7f), 10f * s + sin(time * 10f)*5f)

        // HP Bar (Modern Floating Design)
        val hpPercent = e.currentHp.toFloat() / e.type.hp
        scope.drawRect(Color.Black.copy(0.6f), Offset(-60f*s, -90f*s), Size(120f*s, 8f))
        scope.drawRect(
            Brush.horizontalGradient(listOf(Color(0xFFFF1744), Color(0xFFFFD600))),
            Offset(-60f*s, -90f*s), Size(120f*s * hpPercent, 8f)
        )
    }
}

@Composable
fun UltraUI(engine: UltraGameEngine, selected: TowerType, onSelect: (TowerType) -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        // Üst Panel (Cyberpunk Style)
        Row(Modifier.fillMaxWidth().background(Color.Black.copy(0.6f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("KINGSOT // OVERDRIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
                Text("DALGA: ${engine.wave}", color = Color.Cyan.copy(0.8f), fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CR: ${engine.gold}", color = Color(0xFFFFD600), fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Spacer(Modifier.width(16.dp))
                Text("HP: %${engine.health}", color = if(engine.health > 30) Color.Green else Color.Red, fontWeight = FontWeight.ExtraBold)
            }
        }

        // Seçim Paneli (Bottom)
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).background(Color.Black.copy(0.8f), androidx.compose.foundation.shape.RoundedCornerShape(40.dp)).padding(12.dp)) {
            UltraTowerItem("TESLA", TowerType.TESLA, selected == TowerType.TESLA) { onSelect(TowerType.TESLA) }
            Spacer(Modifier.width(20.dp))
            UltraTowerItem("NOVA", TowerType.NOVA, selected == TowerType.NOVA) { onSelect(TowerType.NOVA) }
        }
    }
    
    if (engine.health <= 0) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.95f)), contentAlignment = Alignment.Center) {
            Text("SİSTEM İMHA EDİLDİ", color = Color.Red, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        }
    }
}

@Composable
fun UltraTowerItem(name: String, type: TowerType, isSelected: Boolean, onClick: () -> Unit) {
    Column(Modifier.pointerInput(Unit) { detectTapGestures { onClick() } }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).background(if(isSelected) type.color.copy(0.3f) else Color.White.copy(0.05f), androidx.compose.foundation.shape.CircleShape)
            .let { if(isSelected) it.background(Color.Transparent, Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))) else it },
            contentAlignment = Alignment.Center) {
            Text(name, color = if(isSelected) Color.White else Color.Gray, fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
        Text("${type.cost} CR", color = Color.White.copy(0.6f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UltraGame() }
    }
}
