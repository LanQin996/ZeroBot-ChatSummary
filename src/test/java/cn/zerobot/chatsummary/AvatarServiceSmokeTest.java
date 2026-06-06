package cn.zerobot.chatsummary;

import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;

public final class AvatarServiceSmokeTest {
    private AvatarServiceSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Settings settings = new Settings();
        settings.setAvatarDownloadTimeoutSeconds(15);
        AvatarService service = new AvatarService(settings, Path.of("build", "tmp", "avatar-service"),
                quietLogger());

        Optional<BufferedImage> group = service.image(AvatarService.Type.GROUP, "956425376");
        Optional<BufferedImage> user = service.image(AvatarService.Type.USER, "2755271615");
        if (group.isEmpty() || user.isEmpty()) {
            throw new IllegalStateException("Failed to download avatar, group=" + group.isPresent()
                    + ", user=" + user.isPresent());
        }
        System.out.println("group=" + group.get().getWidth() + "x" + group.get().getHeight());
        System.out.println("user=" + user.get().getWidth() + "x" + user.get().getHeight());
    }

    static Logger quietLogger() {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[]{Logger.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "avatar-smoke";
                    case "isTraceEnabled", "isDebugEnabled", "isInfoEnabled", "isWarnEnabled", "isErrorEnabled" -> false;
                    default -> null;
                }
        );
    }
}
