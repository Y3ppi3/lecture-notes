# Модели API (kotlinx.serialization): сериализаторы генерируются компилятором,
# но имена полей должны сохраниться для JSON.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep,includedescriptorclasses class ru.lecturenotes.app.network.**$$serializer { *; }
-keepclassmembers class ru.lecturenotes.app.network.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class ru.lecturenotes.app.network.** { *; }

# Retrofit: интерфейсы API и suspend-функции
-keep,allowobfuscation,allowshrinking interface ru.lecturenotes.app.network.** { *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# WorkManager создаёт воркеры по имени класса
-keep class ru.lecturenotes.app.processing.AudioUploadWorker { <init>(...); }

# Логи в релизе не нужны
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Сессия хранится в зашифрованном виде как JSON
-keep class ru.lecturenotes.app.auth.Session { *; }
-keep class ru.lecturenotes.app.auth.Session$$serializer { *; }
