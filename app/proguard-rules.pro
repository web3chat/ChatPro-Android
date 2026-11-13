# ============================================================================
# Chat33Pro R8 / ProGuard 规则
# 说明：这个工程大量依赖反射（ARouter / Room / Gson / protobuf / LitePal / JNI /
#       WebView JS 桥 / LiveData 内部字段），规则缺失会在运行时崩溃。
#       修改后请务必真机回归：登录、聊天、钱包、红包、广场、AI 客服 H5。
# ============================================================================

# ---------- 基础：保留调试与反射所需属性 ----------
# Signature 供 Gson/Retrofit 读取泛型；Annotation 供 ARouter/Room 读取注解
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions
-keepattributes MethodParameters
# 崩溃上报需要行号（Bugly 依赖 mapping + 行号还原堆栈）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- ARouter ----------
-keep public class com.alibaba.android.arouter.routes.**{*;}
-keep class * implements com.alibaba.android.arouter.facade.template.ISyringe{*;}
-keep interface * implements com.alibaba.android.arouter.facade.template.IProvider
-keep class * implements com.alibaba.android.arouter.facade.template.IProvider
-keep @com.alibaba.android.arouter.facade.annotation.Route class *{*;}
-keep @com.alibaba.android.arouter.facade.annotation.Interceptor class *{*;}
-keepclasseswithmembers class * { @com.alibaba.android.arouter.facade.annotation.Autowired <fields>; }

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *{*;}
-keep @androidx.room.Dao interface *{*;}
-keepclassmembers class * { @androidx.room.* <methods>; }

# ---------- LiveData 反射（ChatEventData.kt 用反射改分发版本号）----------
# 该类通过反射读写 androidx 内部字段，混淆后字段名变化会导致崩溃/事件不刷新
-keepclassmembers class androidx.lifecycle.LiveData {
    *** mObservers;
    *** mVersion;
}
-keepclassmembers class androidx.lifecycle.LiveData$ObserverWrapper {
    *** mLastVersion;
}
-keepclassmembers class androidx.arch.core.internal.SafeIterableMap {
    *** get(java.lang.Object);
}

# ---------- WebView JS 桥（H5 按方法名调用）----------
-keep class wendu.dsbridge.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# 工程内的 native 桥（TanxinHost / BizWebBridge）整体保留
-keep class com.fzm.chat.biz.webview.** { *; }

# ---------- JNI / 原生库 ----------
-keepclasseswithmembernames class * { native <methods>; }
# walletapi.aar 自带规则（go.** / walletapi.**）这里再显式声明一次，避免被覆盖
# ---------- TrustWalletCore（wallet-core）：JNI 按字段名访问 ----------
# native 层通过 JNI 读取 Java 对象的 nativeHandle 字段（GetLongField），
# 字段一旦被改名就报 NoSuchFieldError / JNI DETECTED ERROR 直接 abort。
# 典型崩溃：wallet.core.jni.PrivateKey.getPublicKeySecp256k1
-keep class wallet.core.** { *; }
-keepclassmembers class wallet.core.** { *; }
-dontwarn wallet.core.**

-keep class walletapi.** { *; }
-keep class go.** { *; }
-keep class com.tencent.mmkv.** { *; }
-keep class com.tencent.wcdb.** { *; }
-keep class org.spongycastle.** { *; }

# ---------- LitePal（反射建表/赋值）----------
-keep class org.litepal.** { *; }
-keep class * extends org.litepal.crud.LitePalSupport { *; }

# ---------- Gson（字段名即 JSON key，不能混淆）----------
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# 数据模型/传输对象整体保留字段名
-keep class com.fzm.chat.core.net.bean.** { *; }
-keep class com.fzm.chat.core.data.bean.** { *; }
-keep class com.fzm.chat.core.data.model.** { *; }
-keep class com.fzm.chat.**.model.** { *; }

# 工程里大量 Gson 模型没有 @SerializedName，字段名就是 JSON key。
# 只保留类名不够：字段被改名后反序列化结果全是 null（例如广场列表“暂无内容”）。
# 因此对所有业务包保留字段名（类名、方法名仍会被混淆）。
-keepclassmembers class com.fzm.chat.** { <fields>; }
-keepclassmembers class com.fzm.oa.** { <fields>; }
-keepclassmembers class com.fzm.rtc.** { <fields>; }
-keepclassmembers class com.fzm.oss.** { <fields>; }
-keepclassmembers class com.fzm.update.** { <fields>; }
-keepclassmembers class com.lbt.shop.** { <fields>; }

# ---------- 规避 R8 优化导致的 VerifyError ----------
# 关闭优化：这个工程在开启 R8 优化后，PublishSquareActivity.toggleHeading() 等含分支的
# 方法会被改成 ART 无法校验的字节码（java.lang.VerifyError: Verifier rejected class），
# 只对该类 keep 仍会复现，因此整体关闭优化。
# 影响：APK 略大、运行略慢；混淆（改名）与裁剪不受影响，安全性不降低。
-dontoptimize

# 兜底：该 Activity 不参与裁剪/优化（允许改名）
-keep,allowobfuscation class com.fzm.chat.square.PublishSquareActivity { *; }

# ---------- pinyin4j（联系人首字母排序）----------
# 该库通过类名/classpath 资源加载实现，R8 静态分析看不到引用，
# 会把 net.sourceforge.pinyin4j.ResourceHelper 裁掉，导致运行时
# java.lang.NoClassDefFoundError: net.sourceforge.pinyin4j.ResourceHelper
-keep class net.sourceforge.pinyin4j.** { *; }
-dontwarn net.sourceforge.pinyin4j.**

# ---------- web3j + Jackson：字段名与注解必须保留 ----------
# web3j 的 Request.web3jService / responseType 上有 @JsonIgnore，混淆后字段被改名
# 会导致注解失效，Jackson 会继续序列化 HttpService 里的 ObjectMapper，
# 抛 FAIL_ON_EMPTY_BEANS（JsonSetter$Value 无属性）→ 所有 RPC 调用失败，
# 表现为「发红包报错」「红包记录链上查询失败」。
-keep class org.web3j.** { *; }
-keepclassmembers class org.web3j.** { *; }
-dontwarn org.web3j.**

# Jackson 注解必须原样保留，否则 @JsonIgnore / @JsonProperty 等在运行时读不到
-keep class com.fasterxml.jackson.annotation.** { *; }
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes AnnotationDefault

# ---------- protobuf ----------
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 {
    public static ** parseFrom(com.google.protobuf.ByteString);
}
-dontwarn com.google.protobuf.**

# ---------- Parcelable / Serializable ----------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---------- 枚举（values()/valueOf 被反射调用）----------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- 第三方 SDK ----------
-keep class com.tencent.bugly.** { *; }
-dontwarn com.tencent.bugly.**
-keep public class com.umeng.** { *; }
-dontwarn com.umeng.**
-keep class com.alibaba.sdk.android.oss.** { *; }
-keep class com.obs.services.** { *; }
-keep class com.qmuiteam.** { *; }
-keep class com.king.zxing.** { *; }

# ---------- 网络栈 ----------
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ---------- 忽略缺失的可选依赖 ----------
-dontwarn javax.annotation.**
-dontwarn javax.lang.**
-dontwarn javax.naming.**
-dontwarn javax.security.**
-dontwarn org.spongycastle.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.**
-dontwarn com.alibaba.**
-dontwarn org.slf4j.**

# ---------- 可选依赖缺失（来自 R8 的 missing_rules.txt）----------
-dontwarn com.squareup.picasso.Picasso
-dontwarn com.squareup.picasso.RequestCreator
-dontwarn com.tencent.rtmp.liteavsdk.R$attr
-dontwarn com.tencent.rtmp.liteavsdk.R$color
-dontwarn com.tencent.rtmp.liteavsdk.R$dimen
-dontwarn com.tencent.rtmp.liteavsdk.R$drawable
-dontwarn com.tencent.rtmp.liteavsdk.R$layout
-dontwarn com.tencent.rtmp.liteavsdk.R$style
-dontwarn com.tencent.rtmp.liteavsdk.R$styleable
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.**
