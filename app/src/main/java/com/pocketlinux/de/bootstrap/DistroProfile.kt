package com.pocketlinux.de.bootstrap

/**
 * One row in the "pick your distro" UI. Each profile knows:
 *
 *   - The proot-distro alias to use (e.g. "alpine", "debian", "ubuntu")
 *   - Which extra packages to install via the distro's package manager
 *   - The bootstrap script asset that wires up VNC + WM
 *   - Display name and rough size for the picker UI
 *
 * Adding a new profile is one entry here plus one shell script in
 * assets/distros/. The bootstrap scripts share enough structure that we
 * could collapse them into one parameterized template, but keeping them
 * separate makes per-distro debugging massively easier.
 */
data class DistroProfile(
    val id: String,                  // stable identifier persisted in prefs
    val displayName: String,
    val description: String,
    val approxSizeMb: Int,
    val prootDistroName: String,     // what `proot-distro install <X>` expects
    val bootstrapAsset: String,      // filename in assets/distros/
    val recommendedFor: String       // hint shown in picker
) {
    companion object {
        val ALL = listOf(
            DistroProfile(
                id = "alpine-i3",
                displayName = "Alpine + i3",
                description = "Tiny, fast tiling WM. musl libc.",
                approxSizeMb = 150,
                prootDistroName = "alpine",
                bootstrapAsset = "alpine-i3.sh",
                recommendedFor = "Old or low-RAM phones (1-2 GB)"
            ),
            DistroProfile(
                id = "debian-i3",
                displayName = "Debian + i3",
                description = "Tiling WM, vast package selection.",
                approxSizeMb = 400,
                prootDistroName = "debian",
                bootstrapAsset = "debian-i3.sh",
                recommendedFor = "Most users (3+ GB RAM)"
            ),
            DistroProfile(
                id = "debian-xfce",
                displayName = "Debian + XFCE",
                description = "Traditional desktop with panel and menu.",
                approxSizeMb = 900,
                prootDistroName = "debian",
                bootstrapAsset = "debian-xfce.sh",
                recommendedFor = "Newer phones, full desktop feel (4+ GB RAM)"
            ),
            DistroProfile(
                id = "ubuntu-openbox",
                displayName = "Ubuntu + Openbox",
                description = "Familiar Ubuntu base, lightweight WM.",
                approxSizeMb = 600,
                prootDistroName = "ubuntu",
                bootstrapAsset = "ubuntu-openbox.sh",
                recommendedFor = "Ubuntu users wanting something lighter than GNOME"
            )
        )

        fun byId(id: String): DistroProfile? = ALL.firstOrNull { it.id == id }
    }
}
