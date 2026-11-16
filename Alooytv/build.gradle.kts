version = 2

cloudstream {
    description = ""
    authors = listOf("Abodabodd")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie"
    )

    iconUrl = "https://fitnur.com/uploads/avatars/c5ae8dadf88bcea9549657ec451a1563.jpg"
}

dependencies {
    val implementation by configurations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
