plugins { id("gg.grounds.velocity-conventions") }

dependencies {
    compileOnly("gg.grounds:plugin-config-common:1.0.0")
    implementation("gg.grounds:resourcepacks-client:0.3.0")

    testImplementation("gg.grounds:plugin-config-common:1.0.0")
    testImplementation(kotlin("test"))
}
