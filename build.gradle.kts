plugins {
    id("java")
}

group = "net.nedron"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.json:json:20230227")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    compileOnly("org.apache.commons:org.apache.commons.logging:1.0.4.v200706111724")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")
    compileOnly("jakarta.servlet.jsp:jakarta.servlet.jsp-api:4.0.0")
    implementation("it.unimi.di:jsap:20210129")
    implementation("org.eclipse.jetty:jetty-jsp:9.3.0.M1")
    implementation("org.eclipse.jetty:jetty-runner:11.0.25")
    testImplementation("org.testng:testng:7.11.0")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}
