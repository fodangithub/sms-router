# JavaMail 依赖 javax.activation / javax.mail，必须保留，否则发信时找不到类
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.mail.** { *; }
-keep class com.sun.activation.** { *; }

-dontwarn java.awt.**
-dontwarn javax.security.**
-dontwarn org.apache.harmony.**
