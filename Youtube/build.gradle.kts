version = 3

cloudstream {
    description = ""
    authors = listOf("Abodabodd")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Live",
        "Anime",
        "Music",
        "Documentary"
    )

    iconUrl = "https://raw.githubusercontent.com/Abodabodd/Oldarabrepo/refs/heads/main/img/IMG_%D9%A2%D9%A0%D9%A2%D9%A5%D9%A1%D9%A2%D9%A0%D9%A6_%D9%A1%D9%A7%D9%A2%D9%A6%D9%A1%D9%A6.jpg"
}

// Libraries the CloudStream app already ships; the extension compiles against them.
dependencies {
    val implementation by configurations
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.25.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
