# jcifs-ng: reflection-free but references JDK-only classes (JGSS/Kerberos,
# servlet filters) that Android lacks and we never call. Keep the SMB core,
# ignore the missing references.
-keep class jcifs.** { *; }
-dontwarn javax.security.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.servlet.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
