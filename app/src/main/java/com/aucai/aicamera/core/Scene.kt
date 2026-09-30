package com.aucai.aicamera.core

import kotlin.math.abs
import kotlin.math.sqrt

/** One object found by the detector. [label] is the COCO English name; [box] is display-normalized. */
data class ObjectBox(val label: String, val score: Float, val box: RectN)

enum class ObjectGroup(val label: String) {
    FOOD("美食"),
    PET("宠物"),
    PLANT("花草"),
    THING("物品"),
}

enum class SubjectKind { PERSON, OBJECT, HORIZON }

/**
 * What the shot is about.
 * @property label a short Chinese name ("人物", "猫", "地平线").
 * @property anchor the point composition guidance tries to place.
 */
data class Subject(
    val kind: SubjectKind,
    val label: String,
    val anchor: Vec2,
    val box: RectN?,
    val group: ObjectGroup? = null,
)

object Coco {
    private val ZH = mapOf(
        "bicycle" to "自行车", "car" to "汽车", "motorcycle" to "摩托车", "airplane" to "飞机", "bus" to "公交车",
        "train" to "火车", "truck" to "卡车", "boat" to "船", "traffic light" to "红绿灯", "fire hydrant" to "消防栓",
        "stop sign" to "路牌", "parking meter" to "停车咪表", "bench" to "长椅", "bird" to "鸟", "cat" to "猫",
        "dog" to "狗", "horse" to "马", "sheep" to "羊", "cow" to "牛", "elephant" to "大象", "bear" to "熊",
        "zebra" to "斑马", "giraffe" to "长颈鹿", "backpack" to "背包", "umbrella" to "雨伞", "handbag" to "手提包",
        "tie" to "领带", "suitcase" to "行李箱", "frisbee" to "飞盘", "skis" to "滑雪板", "snowboard" to "单板",
        "sports ball" to "球", "kite" to "风筝", "baseball bat" to "球棒", "baseball glove" to "棒球手套",
        "skateboard" to "滑板", "surfboard" to "冲浪板", "tennis racket" to "网球拍", "bottle" to "瓶子",
        "wine glass" to "酒杯", "cup" to "杯子", "fork" to "叉子", "knife" to "刀", "spoon" to "勺子", "bowl" to "碗",
        "banana" to "香蕉", "apple" to "苹果", "sandwich" to "三明治", "orange" to "橙子", "broccoli" to "西兰花",
        "carrot" to "胡萝卜", "hot dog" to "热狗", "pizza" to "披萨", "donut" to "甜甜圈", "cake" to "蛋糕",
        "chair" to "椅子", "couch" to "沙发", "potted plant" to "盆栽", "bed" to "床", "dining table" to "餐桌",
        "toilet" to "马桶", "tv" to "电视", "laptop" to "电脑", "mouse" to "鼠标", "remote" to "遥控器",
        "keyboard" to "键盘", "cell phone" to "手机", "microwave" to "微波炉", "oven" to "烤箱", "toaster" to "烤面包机",
        "sink" to "水槽", "refrigerator" to "冰箱", "book" to "书", "clock" to "钟", "vase" to "花瓶",
        "scissors" to "剪刀", "teddy bear" to "玩偶", "hair drier" to "吹风机", "toothbrush" to "牙刷",
    )
    private val FOOD = setOf(
        "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "bowl", "cup", "wine glass",
    )
    private val PETS = setOf("cat", "dog", "bird", "teddy bear")
    private val PLANTS = setOf("potted plant", "vase")

    /** Furniture and the like: context for a scene, never the thing being photographed. */
    private val NEVER_SUBJECT = setOf(
        "person", "dining table", "bed", "couch", "chair", "bench", "tv", "refrigerator", "oven", "sink", "toilet",
    )

    fun zh(label: String) = ZH[label] ?: label

    fun group(label: String) = when (label) {
        in FOOD -> ObjectGroup.FOOD
        in PETS -> ObjectGroup.PET
        in PLANTS -> ObjectGroup.PLANT
        else -> ObjectGroup.THING
    }

    fun canBeSubject(label: String) = label !in NEVER_SUBJECT
}

object SubjectPicker {

    /** Person first, then the most prominent object, then a horizon. */
    fun pick(pose: PoseFrame?, objects: List<ObjectBox>, luma: LumaGrid?): Subject? {
        if (pose != null && pose.hasShoulders) {
            return Subject(SubjectKind.PERSON, "人物", pose.eyes, pose.bodyBounds())
        }
        bestObject(objects)?.let { o ->
            return Subject(SubjectKind.OBJECT, Coco.zh(o.label), o.box.center, o.box, Coco.group(o.label))
        }
        luma?.let { HorizonDetector.detect(it) }?.let { y ->
            return Subject(SubjectKind.HORIZON, "地平线", Vec2(0.5f, y), null)
        }
        return null
    }

    fun bestObject(objects: List<ObjectBox>): ObjectBox? =
        objects
            .filter { Coco.canBeSubject(it.label) && it.box.width * it.box.height >= 0.01f }
            .maxByOrNull { o ->
                val weight = when (Coco.group(o.label)) {
                    ObjectGroup.PET -> 1.3f
                    ObjectGroup.FOOD -> 1.2f
                    ObjectGroup.PLANT -> 1.0f
                    ObjectGroup.THING -> 0.9f
                }
                weight * o.score * sqrt(o.box.width * o.box.height)
            }
}

/**
 * Finds a straight, bright-above / dark-below boundary across the frame, like a sky line.
 * Returns its y (0..1) or null when there is no convincing one.
 */
object HorizonDetector {
    fun detect(g: LumaGrid): Float? {
        val w = g.width
        val h = g.height
        if (h < 12 || w < 8) return null
        val ys = ArrayList<Int>(w)
        for (i in 0 until w) {
            var best = 0
            var bestY = -1
            for (y in 2 until h - 2) {
                val above = (g.luma[(y - 2) * w + i] + g.luma[(y - 1) * w + i]) / 2
                val below = (g.luma[(y + 1) * w + i] + g.luma[(y + 2) * w + i]) / 2
                val d = above - below
                if (d > best) {
                    best = d
                    bestY = y
                }
            }
            if (best >= 30) ys += bestY
        }
        if (ys.size < w * 0.7f) return null
        ys.sort()
        val median = ys[ys.size / 2]
        if (ys.count { abs(it - median) <= 2 } < w * 0.6f) return null
        if (median < h * 0.12f || median > h * 0.88f) return null
        // The part above must look like sky: bright.
        var sum = 0L
        for (y in 0 until median) for (i in 0 until w) sum += g.luma[y * w + i]
        if (sum.toFloat() / (median * w) < 130f) return null
        return (median + 0.5f) / h
    }
}

/** A one-line description of what the camera sees, plus advice specific to that kind of scene. */
object SceneAdvisor {

    fun describe(subject: Subject?, shot: ShotType?, lighting: LightingResult?): String {
        val parts = ArrayList<String>()
        when (subject?.kind) {
            SubjectKind.PERSON -> {
                parts += "人像"
                parts += when (shot) {
                    ShotType.CLOSE_UP -> "特写"
                    ShotType.HALF_BODY -> "半身"
                    ShotType.FULL_BODY -> "全身"
                    null -> "人物"
                }
            }
            SubjectKind.OBJECT -> {
                parts += subject.group?.label ?: "物品"
                parts += subject.label
            }
            SubjectKind.HORIZON -> parts += "风景"
            null -> parts += "没找到主体"
        }
        if (lighting != null) {
            when {
                lighting.backlit -> parts += "逆光"
                lighting.mean < 50f -> parts += "暗光"
                lighting.highRatio > 0.12f -> parts += "强光"
            }
        }
        return parts.joinToString(" · ")
    }

    fun tips(subject: Subject?, level: LevelState?): List<Tip> {
        if (subject == null || subject.kind != SubjectKind.OBJECT) return emptyList()
        val tips = ArrayList<Tip>()
        val box = subject.box
        val area = box?.let { it.width * it.height } ?: 0f
        val pitch = level?.pitchDeg
        when (subject.group) {
            ObjectGroup.FOOD -> {
                if (pitch != null && pitch < 30f) {
                    tips += Tip("scene.food.angle", TipCategory.COMPOSITION, Severity.SUGGEST, "拍美食：手机往前倾，45°斜拍或俯拍更有食欲")
                }
                if (area < 0.15f) {
                    tips += Tip("scene.food.close", TipCategory.COMPOSITION, Severity.SUGGEST, "手机靠近一点，让食物占满画面")
                }
            }
            ObjectGroup.PET -> if (pitch != null && pitch > 40f) {
                tips += Tip("scene.pet.low", TipCategory.COMPOSITION, Severity.SUGGEST, "手机放低到${subject.label}眼睛的高度，拍出来更生动")
            }
            ObjectGroup.PLANT -> tips += Tip("scene.plant", TipCategory.COMPOSITION, Severity.INFO, "拍花草可以靠近一点，背景越简单越好")
            else -> Unit
        }
        if (box != null) {
            val touches = box.left < 0.01f || box.top < 0.01f || box.right > 0.99f || box.bottom > 0.99f
            if (touches && area < 0.6f) {
                tips += Tip("comp.objcut", TipCategory.COMPOSITION, Severity.SUGGEST, "${subject.label}被画面边缘切到了，手机往后退一点")
            } else if (area < 0.03f && subject.group != ObjectGroup.FOOD) {
                tips += Tip("comp.objsmall", TipCategory.COMPOSITION, Severity.SUGGEST, "${subject.label}太小了，手机靠近一点")
            }
        }
        return tips
    }
}
