-dontobfuscate
-keepattributes SourceFile,LineNumberTable
-keep class xiao.bu.channel.files.FileBrowserPlugin { public *; }
# Keep guarded modern Android calls out of methods verified on Android 4.0.4.
-keep class xiao.bu.channel.files.ModernStorage { *; }
-keep class xiao.bu.channel.files.SmbCryptoProvider$Md4 { public <init>(); }
-keep class xiao.bu.channel.files.SmbCryptoProvider$AesCmac { public <init>(); }
# Dalvik's EnumSet reflects values(); R8 cannot infer this through Android's Enum implementation.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Optional desktop integrations in jcifs/BC are not used by the Android NTLM client.
-dontwarn javax.servlet.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.security.auth.Subject
-dontwarn javax.security.auth.login.**
-dontwarn com.sun.security.**
-dontwarn java.lang.management.**
-dontwarn java.beans.**
-dontwarn java.sql.**
-dontwarn java.awt.**
-dontwarn java.security.cert.CertPathValidatorException$BasicReason
-dontwarn java.security.cert.PKIXRevocationChecker
