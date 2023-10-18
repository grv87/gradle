/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.resolve.verification

import org.gradle.integtests.fixtures.UnsupportedWithConfigurationCache
import org.gradle.security.fixtures.SigningFixtures
import org.gradle.security.internal.Fingerprint
import org.gradle.security.internal.PGPUtils
import org.gradle.security.internal.SecuritySupport
import spock.lang.Issue

import java.util.stream.Collectors
import java.util.stream.Stream

import static org.gradle.security.fixtures.SigningFixtures.signAsciiArmored

class DependencyVerificationSignatureWriteIntegTest extends AbstractSignatureVerificationIntegrationTest {

    def "can generate trusted PGP keys configuration"() {
        createMetadataFile {
            keyServer(keyServerFixture.uri)
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0") {
            withSignature {
                signAsciiArmored(it)
            }
        }
        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }
        """

        when:
        serveValidKey()
        writeVerificationMetadata()

        succeeds ":help"

        then:
        assertXmlContents """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <key-servers>
         <key-server uri="${keyServerFixture.uri}"/>
      </key-servers>
      <trusted-keys>
         <trusted-key id="${SigningFixtures.validPublicKeyHexString}" group="org" name="foo" version="1.0"/>
      </trusted-keys>
   </configuration>
   <components/>
</verification-metadata>
"""
    }

    def "can generate ignored PGP keys configuration"() {
        createMetadataFile {
            keyServer(keyServerFixture.uri)
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0") {
            withSignature {
                signAsciiArmored(it)
            }
        }
        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }
        """

        when:
        writeVerificationMetadata()

        succeeds ":help"

        then:
        assertXmlContents """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <key-servers>
         <key-server uri="${keyServerFixture.uri}"/>
      </key-servers>
      <ignored-keys>
         <ignored-key id="14F53F0824875D73" reason="Key couldn't be downloaded from any key server"/>
      </ignored-keys>
   </configuration>
   <components>
      <component group="org" name="foo" version="1.0">
         <artifact name="foo-1.0.jar">
            <sha256 value="f46001e8577ce4fdaf4d1f9aed03311c581b08f9e82bf2406e70553101680212" origin="Generated by Gradle" reason="A key couldn't be downloaded"/>
         </artifact>
         <artifact name="foo-1.0.pom">
            <sha256 value="f331cce36f6ce9ea387a2c8719fabaf67dc5a5862227ebaa13368ff84eb69481" origin="Generated by Gradle" reason="A key couldn't be downloaded"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
"""
        and:
        output.contains("""A verification file was generated but some problems were discovered:
   - some keys couldn't be downloaded. They were automatically added as ignored keys but you should review if this is acceptable. Look for entries with the following comment: Key couldn't be downloaded from any key server
""")
    }

    def "warns if a signature failed"() {
        createMetadataFile {
            keyServer(keyServerFixture.uri)
        }

        given:
        javaLibrary()
        def module = uncheckedModule("org", "foo", "1.0") {
            withSignature {
                signAsciiArmored(it)
            }
        }
        module.artifactFile.bytes = "corrupted".getBytes("utf-8")

        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }

        """

        when:
        serveValidKey()
        writeVerificationMetadata()

        succeeds ":help"

        then:
        assertXmlContents """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <key-servers>
         <key-server uri="${keyServerFixture.uri}"/>
      </key-servers>
   </configuration>
   <components>
      <component group="org" name="foo" version="1.0">
         <artifact name="foo-1.0.jar">
            <ignored-keys>
               <ignored-key id="${SigningFixtures.validPublicKeyHexString}" reason="PGP verification failed"/>
            </ignored-keys>
            <sha256 value="3dbb3963d11aa418de8b61f846c3dbd5af43b40d252842adb823f90936fe6920" origin="Generated by Gradle" reason="PGP signature verification failed!"/>
         </artifact>
         <artifact name="foo-1.0.pom">
            <pgp value="${SigningFixtures.validPublicKeyHexString}"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
"""
        and:
        output.contains """A verification file was generated but some problems were discovered:
   - some signature verification failed. Checksums were generated for those artifacts but you MUST check if there's an actual problem. Look for entries with the following comment: PGP verification failed
"""
    }

    // plugins do not publish signatures so we expect the checksums to be present
    def "writes checksums of plugins using plugins block"() {
        given:
        addPlugin()
        settingsFile.text = """
        pluginManagement {
            repositories {
                maven {
                    url '$pluginRepo.uri'
                }
            }
        }
        """ + settingsFile.text
        buildFile << """
          plugins {
             id 'test-plugin' version '1.0'
          }
        """

        when:
        writeVerificationMetadata()
        succeeds ':help'

        then:
        assertMetadataExists()
        hasModules(["test-plugin:test-plugin.gradle.plugin", "com:myplugin"])

    }

    // plugins do not publish signatures so we expect the checksums to be present
    def "writes checksums of plugins using buildscript block"() {
        given:
        addPlugin()
        buildFile << """
          buildscript {
             repositories {
                maven { url "${pluginRepo.uri}" }
             }
             dependencies {
                classpath 'com:myplugin:1.0'
             }
          }
        """

        when:
        writeVerificationMetadata()
        succeeds ':help'

        then:
        assertMetadataExists()
        hasModules(["com:myplugin"])
    }

    def "if signature file is missing, generates a checksum"() {
        createMetadataFile {
            keyServer(keyServerFixture.uri)
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0")
        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }
        """

        when:
        writeVerificationMetadata()

        succeeds ":help"

        then:
        hasModules(["org:foo"])
        assertXmlContents """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <key-servers>
         <key-server uri="${keyServerFixture.uri}"/>
      </key-servers>
   </configuration>
   <components>
      <component group="org" name="foo" version="1.0">
         <artifact name="foo-1.0.jar">
            <sha256 value="f46001e8577ce4fdaf4d1f9aed03311c581b08f9e82bf2406e70553101680212" origin="Generated by Gradle" reason="Artifact is not signed"/>
         </artifact>
         <artifact name="foo-1.0.pom">
            <sha256 value="f331cce36f6ce9ea387a2c8719fabaf67dc5a5862227ebaa13368ff84eb69481" origin="Generated by Gradle" reason="Artifact is not signed"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
"""
    }

    def "can export PGP keys"() {
        def keyring = newKeyRing()
        def pkId = Fingerprint.of(keyring.publicKey)
        createMetadataFile {
            keyServer(keyServerFixture.uri)
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0") {
            withSignature {
                keyring.sign(it, [(SigningFixtures.validSecretKey): SigningFixtures.validPassword])
            }
        }
        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }
        """

        when:
        serveValidKey()
        keyServerFixture.registerPublicKey(keyring.publicKey)
        writeVerificationMetadata()

        succeeds ":help", "--export-keys"

        then:
        assertXmlContents """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <key-servers>
         <key-server uri="${keyServerFixture.uri}"/>
      </key-servers>
      <trusted-keys>
         <trusted-key id="${SigningFixtures.validPublicKeyHexString}" group="org" name="foo" version="1.0"/>
         <trusted-key id="$pkId" group="org" name="foo" version="1.0"/>
      </trusted-keys>
   </configuration>
   <components/>
</verification-metadata>
"""
        and:
        def exportedKeyRing = file("gradle/verification-keyring.gpg")
        exportedKeyRing.exists()
        def keyrings = SecuritySupport.loadKeyRingFile(exportedKeyRing)
        keyrings.size() == 2
        keyrings.find { it.publicKey.keyID == SigningFixtures.validPublicKey.keyID }
        keyrings.find { it.publicKey.keyID == keyring.publicKey.keyID }

        and: "also generates an ascii armored keyring file"
        def exportedKeyRingAscii = file("gradle/verification-keyring.keys")
        exportedKeyRingAscii.exists()
        def keyringsAscii = SecuritySupport.loadKeyRingFile(exportedKeyRingAscii)
        keyringsAscii.size() == 2
        keyringsAscii.find { it.publicKey.keyID == SigningFixtures.validPublicKey.keyID }
        keyringsAscii.find { it.publicKey.keyID == keyring.publicKey.keyID }
    }

    def "can export PGP keys in binary format only"() {
        def keyring = newKeyRing()
        def pkId = Fingerprint.of(keyring.publicKey)
        createMetadataFile {
            keyServer(keyServerFixture.uri)
            keyRingFormat("binary")
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0") {
            withSignature {
                keyring.sign(it, [(SigningFixtures.validSecretKey): SigningFixtures.validPassword])
            }
        }
        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }
        """

        when:
        serveValidKey()
        keyServerFixture.registerPublicKey(keyring.publicKey)
        writeVerificationMetadata()

        succeeds ":help", "--export-keys"

        then:
        assertXmlContents """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <keyring-format>binary</keyring-format>
      <key-servers>
         <key-server uri="${keyServerFixture.uri}"/>
      </key-servers>
      <trusted-keys>
         <trusted-key id="${SigningFixtures.validPublicKeyHexString}" group="org" name="foo" version="1.0"/>
         <trusted-key id="$pkId" group="org" name="foo" version="1.0"/>
      </trusted-keys>
   </configuration>
   <components/>
</verification-metadata>
"""
        and:
        def exportedKeyRing = file("gradle/verification-keyring.gpg")
        exportedKeyRing.exists()
        def keyrings = SecuritySupport.loadKeyRingFile(exportedKeyRing)
        keyrings.size() == 2
        keyrings.find { it.publicKey.keyID == SigningFixtures.validPublicKey.keyID }
        keyrings.find { it.publicKey.keyID == keyring.publicKey.keyID }

        and: "does not generate an ascii armored keyring file"
        def exportedKeyRingAscii = file("gradle/verification-keyring.keys")
        !exportedKeyRingAscii.exists()
    }

    def "can export PGP keys in text format only"() {
        def keyring = newKeyRing()
        def pkId = Fingerprint.of(keyring.publicKey)
        createMetadataFile {
            keyServer(keyServerFixture.uri)
            keyRingFormat("armored")
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0") {
            withSignature {
                keyring.sign(it, [(SigningFixtures.validSecretKey): SigningFixtures.validPassword])
            }
        }
        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }
        """

        when:
        serveValidKey()
        keyServerFixture.registerPublicKey(keyring.publicKey)
        writeVerificationMetadata()

        succeeds ":help", "--export-keys"

        then:
        assertXmlContents """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <keyring-format>armored</keyring-format>
      <key-servers>
         <key-server uri="${keyServerFixture.uri}"/>
      </key-servers>
      <trusted-keys>
         <trusted-key id="${SigningFixtures.validPublicKeyHexString}" group="org" name="foo" version="1.0"/>
         <trusted-key id="$pkId" group="org" name="foo" version="1.0"/>
      </trusted-keys>
   </configuration>
   <components/>
</verification-metadata>
"""
        and:
        def exportedKeyRingAscii = file("gradle/verification-keyring.keys")
        exportedKeyRingAscii.exists()
        def keyringsAscii = SecuritySupport.loadKeyRingFile(exportedKeyRingAscii)
        keyringsAscii.size() == 2
        keyringsAscii.find { it.publicKey.keyID == SigningFixtures.validPublicKey.keyID }
        keyringsAscii.find { it.publicKey.keyID == keyring.publicKey.keyID }

        and: "does not generate an binary keyring file"
        def exportedKeyRingBinary = file("gradle/verification-keyring.gpg")
        !exportedKeyRingBinary.exists()
    }

    @UnsupportedWithConfigurationCache
    def "can generate configuration for dependencies resolved in a buildFinished hook"() {
        createMetadataFile {
            keyServer(keyServerFixture.uri)
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0") {
            withSignature {
                signAsciiArmored(it)
            }
        }
        buildFile << """
            println "Adding hook"
            gradle.buildFinished {
               println "Executing hook"
               allprojects {
                   println configurations.detachedConfiguration(dependencies.create("org:foo:1.0")).files
               }
            }
        """

        when:
        serveValidKey()
        writeVerificationMetadata()
        succeeds ":help"

        then:
        assertXmlContents """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <key-servers>
         <key-server uri="${keyServerFixture.uri}"/>
      </key-servers>
      <trusted-keys>
         <trusted-key id="${SigningFixtures.validPublicKeyHexString}" group="org" name="foo" version="1.0"/>
      </trusted-keys>
   </configuration>
   <components/>
</verification-metadata>
"""
    }

    @Issue("https://github.com/gradle/gradle/issues/18394")
    def "doesn't fail exporting keys if any has invalid utf-8 char in user id"() {
        String publicKeyResource = "/org/gradle/integtests/resolve/verification/DependencyVerificationSignatureWriteIntegTest/invalid-utf8-public-key.asc"
        String secretKeyResource = "/org/gradle/integtests/resolve/verification/DependencyVerificationSignatureWriteIntegTest/invalid-utf8-secret-key.asc"
        def keyring = newKeyRingFromResource(publicKeyResource, secretKeyResource)
        keyServerFixture.registerPublicKey(keyring.getPublicKey())
        createMetadataFile {
            keyServer(keyServerFixture.uri)
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0") {
            withSignature {
                keyring.sign(it)
            }
        }
        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }
        """

        when:
        writeVerificationMetadata()

        succeeds ":help", "--export-keys"

        then:
        outputContains("Exported 1 keys to")
    }

    @Issue("https://github.com/gradle/gradle/issues/18567")
    def "--export-keys can export keys even with without --write-verification-metadata"() {
        def keyring = newKeyRing()
        createMetadataFile {
            keyServer(keyServerFixture.uri)
            addTrustedKey("org:foo:1.0.0", SigningFixtures.validPublicKeyHexString)
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0") {
            withSignature {
                keyring.sign(it, [(SigningFixtures.validSecretKey): SigningFixtures.validPassword])
            }
        }
        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }
        """

        when:
        serveValidKey()
        keyServerFixture.registerPublicKey(keyring.publicKey)
        succeeds "build", "--export-keys"

        then:
        outputContains("Exported 1 keys to")
        def exportedKeyRingAscii = file("gradle/verification-keyring.keys")
        exportedKeyRingAscii.exists()
        def keyringsAscii = SecuritySupport.loadKeyRingFile(exportedKeyRingAscii)
        keyringsAscii.size() == 1
        keyringsAscii.find { it.publicKey.keyID == SigningFixtures.validPublicKey.keyID }
    }

    @Issue("https://github.com/gradle/gradle/issues/23607")
    def "verification-keyring.keys contains only necessary data"() {
        def keyring = newKeyRing()
        createMetadataFile {
            keyServer(keyServerFixture.uri)
        }

        given:
        javaLibrary()
        uncheckedModule("org", "foo", "1.0") {
            withSignature {
                keyring.sign(it, [(SigningFixtures.validSecretKey): SigningFixtures.validPassword])
            }
        }
        buildFile << """
            dependencies {
                implementation "org:foo:1.0"
            }
        """

        when:
        serveValidKey()
        keyServerFixture.registerPublicKey(keyring.publicKey)
        writeVerificationMetadata()
        succeeds ":help", "--export-keys"

        then:
        def exportedKeyRingAscii = file("gradle/verification-keyring.keys")
        exportedKeyRingAscii.exists()
        def keyringsAscii = SecuritySupport.loadKeyRingFile(exportedKeyRingAscii)
        keyringsAscii.size() == 2
        keyringsAscii.forEach { keyRing ->
            keyRing.publicKeys.forEachRemaining { publicKey ->
                assert publicKey.getUserAttributes().size() == 0
                assert publicKey.signatures.size() == publicKey.keySignatures.size()
            }
            assert keyRing.publicKey.userIDs.size() == 1
        }
    }

    def "verification-keyring.keys is sorted and deduplicated by keyId"() {
        def count = 50
        def duplicatesCount = 10
        def keyrings = Stream.generate { newKeyRing() }.limit(count - duplicatesCount).collect(Collectors.toList())
        keyrings.addAll(keyrings.subList(0, duplicatesCount))

        createMetadataFile {
            keyServer(keyServerFixture.uri)
        }

        given:
        javaLibrary()
        def dependencies = new StringBuilder()
        keyrings.eachWithIndex { keyring, index ->
            uncheckedModule("org", "foo-$index", "1.0") {
                withSignature {
                    keyring.sign(it, [(SigningFixtures.validSecretKey): SigningFixtures.validPassword])
                }
            }
            dependencies.append("implementation \"org:foo-$index:1.0\"\n")
        }
        buildFile << """
            dependencies {
                $dependencies
            }
        """

        when:
        serveValidKey()
        keyrings.each { keyServerFixture.registerPublicKey(it.publicKey) }
        writeVerificationMetadata()
        succeeds ":help", "--export-keys"

        then:
        def exportedKeyRingAscii = file("gradle/verification-keyring.keys")
        exportedKeyRingAscii.exists()
        def keyringsAscii = SecuritySupport.loadKeyRingFile(exportedKeyRingAscii)
        keyringsAscii.size() == count + 1 - duplicatesCount
        (1..<keyringsAscii.size()).every {
            keyringsAscii[it - 1].publicKey.keyID < keyringsAscii[it].publicKey.keyID
        }
    }

    def "deduplicated keys are chosen by subkeys amount"() {
        given:
        javaLibrary()
        file("gradle/verification-keyring.keys").copyFrom(
            this.class.getResource("/org/gradle/integtests/resolve/verification/DependencyVerificationSignatureWriteIntegTest/duplicated.keys")
        )

        when:
        writeVerificationMetadata()
        succeeds ":help", "--export-keys"

        then:
        def exportedKeyRingAscii = file("gradle/verification-keyring.keys")
        exportedKeyRingAscii.exists()
        def keyringsAscii = SecuritySupport.loadKeyRingFile(exportedKeyRingAscii)
        keyringsAscii.size() == 1
        PGPUtils.getSize(keyringsAscii[0]) == 5
    }
}
