package xyz.block.trailblaze.host.networkcapture

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidCertHashTest {

  @Test
  fun `the CA file name matches what openssl names it`() {
    val cert = CertificateFactory.getInstance("X.509")
      .generateCertificate(SELF_SIGNED_CA.byteInputStream()) as X509Certificate
    // `openssl x509 -subject_hash_old -noout` on this certificate prints d06665f7.
    assertEquals(
      "d06665f7",
      MitmproxyAndroidNetworkCaptureActivator.androidSubjectHashOld(cert.subjectX500Principal.encoded),
    )
  }

  private companion object {
    /** A throwaway self-signed `O=mitmproxy, CN=mitmproxy` certificate; its key was discarded. */
    val SELF_SIGNED_CA = """
      -----BEGIN CERTIFICATE-----
      MIIDMTCCAhmgAwIBAgIUY6CHHeFpkIIhBFZ2ocRwhWeWpP8wDQYJKoZIhvcNAQEL
      BQAwKDESMBAGA1UECgwJbWl0bXByb3h5MRIwEAYDVQQDDAltaXRtcHJveHkwHhcN
      MjYxMDEzMDIyMDQxWhcNMzYxMDEwMDIyMDQxWjAoMRIwEAYDVQQKDAltaXRtcHJv
      eHkxEjAQBgNVBAMMCW1pdG1wcm94eTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC
      AQoCggEBAJ83W2kqVivhPigGfwsSfkPo6DQO/KW+ljQJ5g2tL3OAgrM/nBa3Cwa0
      PIT5/NUl4B5loXUzwxdXMvPwvvDe6Z80wONM/J2ZMdm90wDwftOD4pFYN+o9WMsX
      +sPTiiA7SYEWXSJe1+JJhEiawjK7TH3f+auxa7zGOkc2jEb1H82asVN/V/cGqis8
      1IGR2f1lm4dnf2mi/mewJbmpEynZsmrNaNc6a2riiMhlvV8epY/EdlDJ5tptxrXX
      NEflD73tDWhMWrAcLTqCsBbLNMFZX+rHxd30GYwt+FrDMNhHBTHw/LASzwkU4Pe3
      Mbw5GEsNIEKBMzL9AYIKWwEbQNLYTm0CAwEAAaNTMFEwHQYDVR0OBBYEFNKuzviA
      xGql9wp0zmkmKAhzq56zMB8GA1UdIwQYMBaAFNKuzviAxGql9wp0zmkmKAhzq56z
      MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAJP208ZMeMLrjmpD
      xmElJ/1Z5imKYgN2I8xuCeQ60YfWVS8Vlz8ryXC6sQdjbaO3Id3KzeOI0QZuNcxr
      GxkEA5MK8y1mcHbpzRSgRL2Fh4f+/eOIHNlkY6N1JGWpy8ZcW7oVeXeAzvqCSjbY
      aRH4wANvGm0fMgDi5q0fWxACZIsUVhX+NVw+zW58finsIqtRKld+k/Q1BZDQzEt/
      p6pc2DQeggmEqzi6srK+qfjOzAqRkMMAA123n3oyRauIR/t1CT/fr5SbvIHq6qIP
      eQ6XPOeRxpiro7xtKTfYo33d8+ZEshLkoMwyVvKexsjbVCCIBM+jZwqV2j5fdnOG
      092K7cQ=
      -----END CERTIFICATE-----
    """.trimIndent()
  }
}
