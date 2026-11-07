package dev.cobblesynergy

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/** 테스트 빌드 여부 (build.properties 의 test=true). 테스트 빌드에서만 로그 파일과 테스트 전투 버튼이 켜진다. */
object TestMode {
    val enabled: Boolean by lazy {
        runCatching {
            val props = Properties()
            TestMode::class.java.getResourceAsStream("/cobblesynergy/build.properties")?.use { props.load(it) }
            props.getProperty("test") == "true"
        }.getOrDefault(false)
    }
    val version: String by lazy {
        FabricLoader.getInstance().getModContainer("cobblesynergy").map { it.metadata.version.friendlyString }.orElse("?")
    }
}

/**
 * <게임 폴더>/logs/cobblesynergy-test.log 에 한 줄씩 쌓는다.
 * 클라이언트와 (싱글플레이) 내장 서버가 같은 파일을 쓰므로 동기화한다.
 */
object TestLog {
    private val file = FabricLoader.getInstance().gameDir.resolve("logs").resolve("cobblesynergy-test.log")
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @Synchronized
    fun log(tag: String, message: String) {
        if (!TestMode.enabled) return
        runCatching {
            Files.createDirectories(file.parent)
            val side = if (Thread.currentThread().name.contains("Server", ignoreCase = true)) "server" else "client"
            Files.writeString(
                file,
                "${LocalDateTime.now().format(time)} [$side/$tag] $message\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        }
    }

    fun result(name: String, ok: Boolean, detail: String = "") =
        log("check", "${if (ok) "PASS" else "FAIL"} $name${if (detail.isNotEmpty()) " — $detail" else ""}")

    val path get() = file.toString()

    /** 전투 엔진 메시지 전체를 별도 파일(cobblesynergy-dump.log)에 남긴다 */
    @Synchronized
    fun dump(title: String, lines: List<String>) {
        if (!TestMode.enabled) return
        runCatching {
            val f = file.resolveSibling("cobblesynergy-dump.log")
            val nl = System.lineSeparator()
            Files.writeString(f, "===== $title (${lines.size}줄) =====$nl" + lines.joinToString(nl) + nl + nl,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}
