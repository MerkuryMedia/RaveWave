package com.ravewave.app.scene

enum class VisualLayer(val displayName: String, val category: LayerCategory, val index: Int) {
    SPECTRUM("Spectrum", LayerCategory.BASE, 0),
    WAVEFORM("Waveform", LayerCategory.BASE, 1),
    CIRCULAR("Circular", LayerCategory.BASE, 2),
    VU("VU", LayerCategory.BASE, 3),
    GRAVITY("Gravity", LayerCategory.BASE, 4),
    DNA("DNA", LayerCategory.BASE, 5),
    LIQUID("Liquid", LayerCategory.BASE, 6),
    CITY("City", LayerCategory.BASE, 7),
    NEURAL("Neural", LayerCategory.BASE, 8),
    ORBIT("Orbit", LayerCategory.BASE, 9),
    BLACK_HOLE("Black Hole", LayerCategory.BASE, 10),
    CRYSTAL("Crystal", LayerCategory.BASE, 11),
    FIRE("Fire", LayerCategory.BASE, 12),
    MAGNETIC("Magnetic", LayerCategory.BASE, 13),

    MANDALA("Mandala Wave", LayerCategory.SACRED, 14),
    LOTUS("Lotus Petals", LayerCategory.SACRED, 15),
    METATRON("Metatron Grid", LayerCategory.SACRED, 16),
    YANTRA("Yantra Star", LayerCategory.SACRED, 17),

    SPIRAL("Spiral Galaxy", LayerCategory.EXTRA, 18),
    LASER("Laser Grid", LayerCategory.EXTRA, 19),
    RINGS("Pulse Rings", LayerCategory.EXTRA, 20),

    SWARM("Swarm", LayerCategory.MUSIC_OBJECT, 21),
    BOUNCERS("Bouncing Orbs", LayerCategory.MUSIC_OBJECT, 22),
    COMETS("Comets", LayerCategory.MUSIC_OBJECT, 23),
    SHARDS("Shards", LayerCategory.MUSIC_OBJECT, 24),
    ORBITERS("Orbiters", LayerCategory.MUSIC_OBJECT, 25),
    RIBBONS("Ribbons", LayerCategory.MUSIC_OBJECT, 26),
    RAIN("Neon Rain", LayerCategory.MUSIC_OBJECT, 27),
    BUBBLES("Bass Bubbles", LayerCategory.MUSIC_OBJECT, 28)
}
