# jcifs-ng: reflection-free but references JDK-only classes (JGSS/Kerberos,
# servlet filters) that Android lacks and we never call. Keep the SMB core,
# ignore the missing references.
-keep class jcifs.** { *; }
-dontwarn javax.security.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.servlet.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

# The bundled BouncyCastle provider is looked up by name and reflection
# (MessageDigest.getInstance("MD4")); keep the provider and its digests.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.crypto.digests.** { *; }
