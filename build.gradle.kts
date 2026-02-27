import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") version "2.7.2"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "com.lhstack"
version = "0.0.5"


repositories {
    intellijPlatform {
        defaultRepositories()
    }
    mavenLocal()
    maven("https://maven.aliyun.com/repository/public/")
    mavenCentral()
}
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.46.0.0") {
        // Avoid bundling slf4j classes into the plugin jar; let the IDE provide them.
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("org.openjdk.nashorn:nashorn-core:15.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.apache.pdfbox:pdfbox:2.0.31")
    implementation(files("/Users/hl/.jtools/sdk/sdk.jar"))
    intellijPlatform{
        intellijIdeaCommunity("2022.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.properties")
    }
}


tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }
    withType<JavaExec> {
        jvmArgs("-Dfile.encoding=UTF-8")
    }

    withType<Jar>(){
        archiveBaseName = "jtools-https"
    }

    withType<ShadowJar> {
        archiveClassifier.set("")
        transform(com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer::class.java)
        transform(com.github.jengelman.gradle.plugins.shadow.transformers.XmlAppendingTransformer::class.java)
        transform(com.github.jengelman.gradle.plugins.shadow.transformers.XmlAppendingTransformer::class.java)
        exclude("META-INF/MANIFEST.MF","META-INF/*.SF","META-INF/*.DSA")
        // Ensure slf4j classes are not packaged into the shadow jar.
        exclude("org/slf4j/**")
        dependencies {
            exclude(dependency("com.jetbrains.*:.*:.*"))
            exclude(dependency("org.jetbrains.*:.*:.*"))
        }
    }
    named<Jar>("jar") {
        enabled = true
        archiveClassifier.set("base")
    }
    named("assemble") {
        dependsOn("shadowJar")
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions{
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }


}
tasks.test {
    useJUnitPlatform()
}
