package com.aucai.aicamera.core

import kotlin.math.max
import kotlin.math.min

/** What kind of scene the camera is looking at, like a phone maker's "AI scene" badge. */
enum class SceneKind(val label: String) {
    PORTRAIT("人像"),
    FOOD("美食"),
    CAT("猫"),
    DOG("狗"),
    ANIMAL("动物"),
    FLOWER("花卉"),
    GREENERY("绿植"),
    BLUE_SKY("蓝天"),
    SUNSET("日落"),
    NIGHT("夜景"),
    SNOW("雪景"),
    WATER("水边"),
    MOUNTAIN("山景"),
    BUILDING("建筑"),
    DOCUMENT("文档"),
    OBJECT("物品"),
    INDOOR("室内"),
    OUTDOOR("户外"),
    UNKNOWN(""),
}

/** @property detail a finer name when known, e.g. "披萨" for food or "柯基" for a dog. */
data class SceneGuess(val kind: SceneKind, val detail: String? = null)

/** One result from the image classifier (ImageNet classes). */
data class ClassifierHit(val index: Int, val name: String, val score: Float)

/** Groups of ImageNet classes that say something about the scene, with Chinese names for the common ones. */
object ImageNet {

    enum class Group { DOG, CAT, ANIMAL, FOOD, FLOWER, WATER, MOUNTAIN, BUILDING, DOCUMENT, INDOOR }

    private val GROUPS: Map<String, Group> = buildMap {
        for (n in listOf(
            "cock", "hen", "peacock", "macaw", "sulphur-crested cockatoo", "lorikeet", "hummingbird", "goose",
            "wood rabbit", "hare", "hamster", "guinea pig", "goldfish",
        )) put(n, Group.ANIMAL)
        for (n in listOf(
            "plate", "guacamole", "consomme", "hot pot", "trifle", "ice cream", "ice lolly", "French loaf", "bagel",
            "pretzel", "cheeseburger", "hotdog", "mashed potato", "head cabbage", "broccoli", "cauliflower", "zucchini",
            "spaghetti squash", "acorn squash", "butternut squash", "cucumber", "artichoke", "bell pepper", "cardoon",
            "mushroom", "Granny Smith", "strawberry", "orange", "lemon", "fig", "pineapple", "banana", "jackfruit",
            "custard apple", "pomegranate", "carbonara", "chocolate sauce", "dough", "meat loaf", "pizza", "potpie",
            "burrito", "red wine", "espresso", "eggnog", "soup bowl", "wok", "frying pan", "Crock Pot",
            "hen-of-the-woods", "coffee mug", "teapot", "mixing bowl", "honeycomb", "bakery",
        )) put(n, Group.FOOD)
        for (n in listOf("daisy", "yellow lady's slipper", "rapeseed", "pot", "vase")) put(n, Group.FLOWER)
        for (n in listOf(
            "lakeside", "seashore", "sandbar", "promontory", "coral reef", "breakwater", "pier", "boathouse",
        )) put(n, Group.WATER)
        for (n in listOf("alp", "valley", "volcano", "cliff", "geyser")) put(n, Group.MOUNTAIN)
        for (n in listOf(
            "church", "palace", "castle", "monastery", "mosque", "dome", "triumphal arch", "bell cote", "barn",
            "planetarium", "cinema", "prison", "tile roof", "steel arch bridge", "suspension bridge", "viaduct",
            "obelisk", "stupa", "megalith", "beacon", "water tower", "yurt", "thatch", "fountain", "dam",
        )) put(n, Group.BUILDING)
        for (n in listOf(
            "menu", "book jacket", "envelope", "web site", "comic book", "crossword puzzle", "binder",
        )) put(n, Group.DOCUMENT)
        for (n in listOf(
            "library", "restaurant", "grocery store", "home theater", "studio couch", "wardrobe", "bookcase", "desk",
            "dining table", "shoji", "window shade", "lampshade", "altar", "desktop computer", "monitor", "screen",
        )) put(n, Group.INDOOR)
    }

    private val ZH: Map<String, String> = mapOf(
        // Dogs
        "Chihuahua" to "吉娃娃", "Maltese dog" to "马尔济斯", "Pekinese" to "京巴", "Shih-Tzu" to "西施犬",
        "papillon" to "蝴蝶犬", "beagle" to "比格", "Yorkshire terrier" to "约克夏", "miniature schnauzer" to "雪纳瑞",
        "standard schnauzer" to "雪纳瑞", "giant schnauzer" to "雪纳瑞", "West Highland white terrier" to "西高地",
        "golden retriever" to "金毛", "Labrador retriever" to "拉布拉多", "cocker spaniel" to "可卡",
        "Border collie" to "边牧", "collie" to "苏牧", "Shetland sheepdog" to "喜乐蒂", "German shepherd" to "德牧",
        "Rottweiler" to "罗威纳", "Doberman" to "杜宾", "French bulldog" to "法斗", "boxer" to "拳师犬",
        "Great Dane" to "大丹", "Saint Bernard" to "圣伯纳", "malamute" to "阿拉斯加", "Siberian husky" to "哈士奇",
        "Eskimo dog" to "哈士奇", "dalmatian" to "斑点狗", "pug" to "巴哥", "Samoyed" to "萨摩耶",
        "Pomeranian" to "博美", "chow" to "松狮", "Pembroke" to "柯基", "Cardigan" to "柯基",
        "toy poodle" to "泰迪", "miniature poodle" to "泰迪", "standard poodle" to "贵宾", "Tibetan mastiff" to "藏獒",
        "Bernese mountain dog" to "伯恩山", "Old English sheepdog" to "古牧", "Japanese spaniel" to "狆",
        "basenji" to "巴仙吉", "keeshond" to "荷兰毛狮",
        // Cats
        "tabby" to "狸花猫", "tiger cat" to "虎斑猫", "Persian cat" to "波斯猫", "Siamese cat" to "暹罗猫",
        "Egyptian cat" to "短毛猫",
        // Other animals
        "cock" to "公鸡", "hen" to "母鸡", "peacock" to "孔雀", "macaw" to "金刚鹦鹉", "sulphur-crested cockatoo" to "鹦鹉",
        "lorikeet" to "鹦鹉", "hummingbird" to "蜂鸟", "goose" to "鹅", "wood rabbit" to "兔子", "hare" to "兔子",
        "hamster" to "仓鼠", "guinea pig" to "豚鼠", "goldfish" to "金鱼",
        // Food
        "plate" to "一盘菜", "guacamole" to "牛油果酱", "consomme" to "汤", "hot pot" to "火锅", "trifle" to "甜品",
        "ice cream" to "冰淇淋", "ice lolly" to "冰棍", "French loaf" to "面包", "bagel" to "贝果", "pretzel" to "面包",
        "cheeseburger" to "汉堡", "hotdog" to "热狗", "mashed potato" to "土豆泥", "head cabbage" to "蔬菜",
        "broccoli" to "西兰花", "cauliflower" to "花菜", "zucchini" to "蔬菜", "cucumber" to "黄瓜",
        "bell pepper" to "彩椒", "mushroom" to "蘑菇", "Granny Smith" to "苹果", "strawberry" to "草莓",
        "orange" to "橙子", "lemon" to "柠檬", "fig" to "无花果", "pineapple" to "菠萝", "banana" to "香蕉",
        "pomegranate" to "石榴", "carbonara" to "意面", "chocolate sauce" to "甜品", "meat loaf" to "肉",
        "pizza" to "披萨", "potpie" to "派", "burrito" to "卷饼", "red wine" to "红酒", "espresso" to "咖啡",
        "eggnog" to "饮品", "soup bowl" to "汤", "wok" to "炒菜", "frying pan" to "煎锅菜", "Crock Pot" to "炖菜",
        "coffee mug" to "咖啡", "teapot" to "茶", "bakery" to "面包",
        // Flowers
        "daisy" to "雏菊", "yellow lady's slipper" to "兰花", "rapeseed" to "油菜花",
        // Water and mountains
        "lakeside" to "湖边", "seashore" to "海边", "sandbar" to "沙滩", "promontory" to "海岬", "coral reef" to "海底",
        "breakwater" to "海堤", "pier" to "码头", "alp" to "雪山", "valley" to "山谷", "volcano" to "火山",
        "cliff" to "悬崖",
        // Buildings
        "church" to "教堂", "palace" to "宫殿", "castle" to "城堡", "monastery" to "寺院", "mosque" to "清真寺",
        "dome" to "穹顶", "triumphal arch" to "拱门", "steel arch bridge" to "桥", "suspension bridge" to "桥",
        "viaduct" to "高架桥", "stupa" to "佛塔", "beacon" to "灯塔", "fountain" to "喷泉", "dam" to "大坝",
        // Documents
        "menu" to "菜单", "book jacket" to "书", "envelope" to "信封", "web site" to "屏幕", "comic book" to "漫画",
    )

    fun group(hit: ClassifierHit): Group? = when (hit.index) {
        in 151..268 -> Group.DOG
        in 281..285 -> Group.CAT
        else -> GROUPS[hit.name]
    }

    fun zh(name: String): String? = ZH[name]
}

/**
 * Colour make-up of the frame, from the small grid: how much looks like blue sky (top part),
 * foliage, a sunset (upper half), snow, or strongly coloured things such as flowers (below the
 * sky band; not green, not sky blue). Fractions of the relevant part of the frame.
 */
data class ColorStats(
    val skyBlue: Float,
    val green: Float,
    val sunset: Float,
    val snow: Float,
    val colorful: Float,
) {
    companion object {
        val NONE = ColorStats(0f, 0f, 0f, 0f, 0f)

        fun from(g: LumaGrid): ColorStats {
            val rgb = g.rgb ?: return NONE
            val topRows = max(1, (g.height * 0.4f).toInt())
            val upperRows = max(1, g.height / 2)
            var sky = 0
            var green = 0
            var sunset = 0
            var snow = 0
            var colorful = 0
            val hsv = FloatArray(3)
            for (j in 0 until g.height) {
                for (i in 0 until g.width) {
                    hsv(rgb[j * g.width + i], hsv)
                    val h = hsv[0]
                    val s = hsv[1]
                    val v = hsv[2]
                    val leafy = h in 65f..170f
                    if (j < topRows && h in 185f..250f && s > 0.12f && v > 0.45f) sky++
                    if (j < upperRows && (h < 50f || h > 320f) && s > 0.35f && v > 0.35f) sunset++
                    if (leafy && s > 0.18f && v in 0.12f..0.9f) green++
                    if (v > 0.78f && s < 0.12f) snow++
                    if (j >= topRows && !leafy && h !in 180f..250f && s > 0.45f && v > 0.3f) colorful++
                }
            }
            val n = (g.width * g.height).toFloat()
            return ColorStats(
                sky.toFloat() / (topRows * g.width),
                green / n,
                sunset.toFloat() / (upperRows * g.width),
                snow / n,
                colorful.toFloat() / max(1, (g.height - topRows) * g.width),
            )
        }

        /** Hue 0..360, saturation and value 0..1 of 0xRRGGBB. */
        fun hsv(c: Int, out: FloatArray) {
            val r = ((c shr 16) and 0xff) / 255f
            val g = ((c shr 8) and 0xff) / 255f
            val b = (c and 0xff) / 255f
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val d = mx - mn
            out[0] = hue(r, g, b, mx, d)
            out[1] = if (mx <= 0f) 0f else d / mx
            out[2] = mx
        }

        fun hue(r: Float, g: Float, b: Float, mx: Float, d: Float): Float {
            if (d < 1e-4f) return 0f
            val h = when (mx) {
                r -> ((g - b) / d).let { if (it < 0f) it + 6f else it }
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            return 60f * h
        }
    }
}

/** Everything the scene recognizer looks at for one frame. */
class SceneEvidence(
    val person: Boolean,
    val objects: List<ObjectBox>,
    val classes: List<ClassifierHit>,
    val color: ColorStats,
    /** Mean luma 0..255 of the frame. */
    val mean: Float,
    /** Scene brightness (EV at ISO 100); null when unknown. */
    val ev: Float?,
)

/**
 * Decides the scene from the detectors, the image classifier, the colours and how bright it really
 * is (indoors and outdoors look alike once the camera has adjusted its exposure).
 */
object SceneRecognizer {

    const val OUTDOOR_EV = 8.5f
    const val NIGHT_EV = 3.5f

    fun recognize(e: SceneEvidence): SceneGuess {
        if (e.person) return SceneGuess(SceneKind.PORTRAIT)
        val outdoor: Boolean? = e.ev?.let { it >= OUTDOOR_EV }
        val night = e.ev?.let { it < NIGHT_EV } ?: (e.mean < 35f)

        val byGroup = HashMap<ImageNet.Group, Float>()
        val topOf = HashMap<ImageNet.Group, ClassifierHit>()
        for (h in e.classes) {
            val g = ImageNet.group(h) ?: continue
            byGroup[g] = (byGroup[g] ?: 0f) + h.score
            if ((topOf[g]?.score ?: 0f) < h.score) topOf[g] = h
        }
        fun score(g: ImageNet.Group) = byGroup[g] ?: 0f
        fun name(g: ImageNet.Group, min: Float) = topOf[g]?.takeIf { it.score >= min }?.let { ImageNet.zh(it.name) }

        val big = e.objects.filter { it.box.width * it.box.height >= 0.02f }
        fun coco(vararg labels: String) = big.filter { it.label in labels }.maxByOrNull { it.score * it.box.width * it.box.height }

        coco("cat")?.let { return SceneGuess(SceneKind.CAT, name(ImageNet.Group.CAT, 0.3f)) }
        if (score(ImageNet.Group.CAT) >= 0.4f) return SceneGuess(SceneKind.CAT, name(ImageNet.Group.CAT, 0.3f))
        coco("dog")?.let { return SceneGuess(SceneKind.DOG, name(ImageNet.Group.DOG, 0.3f)) }
        if (score(ImageNet.Group.DOG) >= 0.4f) return SceneGuess(SceneKind.DOG, name(ImageNet.Group.DOG, 0.3f))
        coco("bird", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")?.let {
            return SceneGuess(SceneKind.ANIMAL, Coco.zh(it.label))
        }
        if (score(ImageNet.Group.ANIMAL) >= 0.4f) return SceneGuess(SceneKind.ANIMAL, name(ImageNet.Group.ANIMAL, 0.3f))

        val food = big.filter { Coco.group(it.label) == ObjectGroup.FOOD }.maxByOrNull { it.score * it.box.width * it.box.height }
        if (food != null || score(ImageNet.Group.FOOD) >= 0.35f) {
            return SceneGuess(SceneKind.FOOD, name(ImageNet.Group.FOOD, 0.25f) ?: food?.let { Coco.zh(it.label) })
        }
        if (night) return SceneGuess(SceneKind.NIGHT)
        if (score(ImageNet.Group.DOCUMENT) >= 0.35f) return SceneGuess(SceneKind.DOCUMENT, name(ImageNet.Group.DOCUMENT, 0.3f))

        val c = e.color
        val pot = coco("potted plant", "vase")
        val blooms = c.colorful >= 0.12f && c.colorful <= 0.6f && c.green >= 0.15f && c.sunset < 0.1f
        if (score(ImageNet.Group.FLOWER) >= 0.3f || (pot != null && c.colorful >= 0.06f) || blooms) {
            return SceneGuess(SceneKind.FLOWER, name(ImageNet.Group.FLOWER, 0.3f))
        }

        // Landscapes only outdoors (or when brightness is unknown, on stronger colour evidence).
        if (outdoor != false) {
            val sure = outdoor == true
            if (c.sunset >= (if (sure) 0.18f else 0.3f)) return SceneGuess(SceneKind.SUNSET)
            if (sure && c.snow >= 0.35f && (e.ev ?: 0f) >= 11f) return SceneGuess(SceneKind.SNOW)
            if (score(ImageNet.Group.WATER) >= 0.3f) return SceneGuess(SceneKind.WATER, name(ImageNet.Group.WATER, 0.25f))
            if (score(ImageNet.Group.MOUNTAIN) >= 0.3f) return SceneGuess(SceneKind.MOUNTAIN, name(ImageNet.Group.MOUNTAIN, 0.25f))
            if (score(ImageNet.Group.BUILDING) >= 0.3f) return SceneGuess(SceneKind.BUILDING, name(ImageNet.Group.BUILDING, 0.25f))
            if (c.skyBlue >= (if (sure) 0.3f else 0.45f)) return SceneGuess(SceneKind.BLUE_SKY)
            if (c.green >= (if (sure) 0.3f else 0.45f)) return SceneGuess(SceneKind.GREENERY)
        }
        if (c.green >= 0.45f) return SceneGuess(SceneKind.GREENERY)
        SubjectPicker.bestObject(e.objects)?.let { return SceneGuess(SceneKind.OBJECT, Coco.zh(it.label)) }
        return when (outdoor) {
            true -> SceneGuess(SceneKind.OUTDOOR)
            false -> SceneGuess(SceneKind.INDOOR)
            null -> if (score(ImageNet.Group.INDOOR) >= 0.3f) SceneGuess(SceneKind.INDOOR) else SceneGuess(SceneKind.UNKNOWN)
        }
    }

    /** Landscape-type scenes: framed wide, with the horizon in mind. */
    fun isLandscape(k: SceneKind) = k in setOf(
        SceneKind.BLUE_SKY, SceneKind.SUNSET, SceneKind.SNOW, SceneKind.WATER, SceneKind.MOUNTAIN, SceneKind.OUTDOOR,
    )

    /** One line of advice for a scene with nothing to aim at. */
    fun tip(k: SceneKind): String? = when (k) {
        SceneKind.BLUE_SKY -> "蓝天：让天空占画面上方三分之二，更开阔"
        SceneKind.SUNSET -> "日落：已稍微压暗，颜色更浓；可以找个剪影做前景"
        SceneKind.NIGHT -> "夜景：拿稳手机，靠着东西拍更清楚"
        SceneKind.GREENERY -> "绿植：找有阳光透过的叶子，背景暗一点更突出"
        SceneKind.FLOWER -> "花卉：靠近一点，让花占画面一半，背景越简单越好"
        SceneKind.SNOW -> "雪景：已自动提亮，雪不会拍成灰色"
        SceneKind.WATER -> "水边：把水平线放在三分线上，注意拉平"
        SceneKind.MOUNTAIN -> "山景：前景放点东西（树、石头、人），画面更有层次"
        SceneKind.BUILDING -> "建筑：手机竖直别仰拍，线条才不会歪"
        SceneKind.DOCUMENT -> "文档：手机和纸面平行，正对着拍"
        else -> null
    }
}

/**
 * Smooths the per-frame guesses: a new scene has to win over several frames before the badge
 * changes, and the finer name is the one seen most lately.
 */
class SceneTracker(private val alpha: Float = 0.3f, private val margin: Float = 0.2f) {

    private val score = FloatArray(SceneKind.entries.size)
    private var current = SceneKind.UNKNOWN
    private val details = HashMap<String, Float>()

    fun reset() {
        score.fill(0f)
        current = SceneKind.UNKNOWN
        details.clear()
    }

    fun update(guess: SceneGuess): SceneGuess {
        for (k in SceneKind.entries) {
            val target = if (k == guess.kind) 1f else 0f
            score[k.ordinal] += alpha * (target - score[k.ordinal])
        }
        val best = SceneKind.entries.maxBy { score[it.ordinal] }
        if (best != current && (score[best.ordinal] > score[current.ordinal] + margin || score[current.ordinal] < 0.25f)) {
            current = best
            details.clear()
        }
        if (guess.kind == current) {
            for (key in details.keys.toList()) details[key] = details.getValue(key) * (1f - alpha)
            guess.detail?.let { details[it] = (details[it] ?: 0f) + alpha }
        }
        val detail = details.entries.filter { it.value >= 0.25f }.maxByOrNull { it.value }?.key
        return SceneGuess(current, detail)
    }
}
