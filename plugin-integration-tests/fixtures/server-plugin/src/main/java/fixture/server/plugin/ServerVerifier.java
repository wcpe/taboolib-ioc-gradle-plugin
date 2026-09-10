package fixture.server.plugin;

import java.io.File;
import java.util.List;
import top.wcpe.mc.testkit.harness.McTestkitEnv;
import top.wcpe.mc.testkit.harness.McTestkitResultWriter;
import top.wcpe.taboolib.ioc.annotation.Component;
import top.wcpe.taboolib.ioc.annotation.Inject;
import top.wcpe.taboolib.ioc.annotation.PostEnable;
import top.wcpe.taboolib.ioc.bean.BeanContainer;

@Component
class ServerVerifier {

    /** 失败标记（保留给无编排手工直跑时的 stdout 兜底，便于本地调试）。 */
    private static final String FAIL_PREFIX = "[IoC-Server-Test] FAIL: ";

    /** 成功标记（保留给无编排手工直跑时的 stdout 兜底，便于本地调试）。 */
    private static final String PASS_MARKER = "[IoC-Server-Test] PASS";

    @Inject
    FieldInjectedConsumer fieldConsumer;

    @Inject
    NamedGatewayConsumer namedConsumer;

    @PostEnable
    void verify() {
        try {
            check(fieldConsumer != null && fieldConsumer.ready(), "field/constructor injection failed");
            check(namedConsumer != null && namedConsumer.ready(), "named injection failed");
            check(LifecycleProbe.initialized, "post construct did not run");

            ConstructorInjectedService first = BeanContainer.INSTANCE.getBean(ConstructorInjectedService.class, null);
            ConstructorInjectedService second = BeanContainer.INSTANCE.getBean(ConstructorInjectedService.class, null);
            check(first != null && first == second, "singleton lookup failed");

            PrototypeCounterBean p1 = BeanContainer.INSTANCE.getBean(PrototypeCounterBean.class, null);
            PrototypeCounterBean p2 = BeanContainer.INSTANCE.getBean(PrototypeCounterBean.class, null);
            check(p1 != null && p2 != null && p1 != p2, "prototype scope failed");

            List<ServerGateway> gateways = BeanContainer.INSTANCE.getBeansOfType(ServerGateway.class);
            check(gateways.size() == 2, "getBeansOfType failed: " + gateways.size());
        } catch (IllegalStateException exception) {
            // 判定真源 = 结果文件；失败同样先落盘再关服，编排按 status=FAIL 抛错。
            finish(false, exception.getMessage());
            shutdownServer();
            return;
        }

        finish(true, "ioc injection ok");
        shutdownServer();
    }

    /**
     * 写出场景判定结论。
     *
     * <p>主路径：从 {@code MC_TESTKIT_E2E_RESULT_FILE} 读结果文件绝对路径，用
     * {@link McTestkitResultWriter} 原子写 {@code status=/message=}（编排 verify 只认它）。
     * 兜底分支：env 缺失（如绕过编排手工直跑）时沿用旧 stdout 约定，便于本地调试。
     */
    private void finish(boolean pass, String message) {
        String resultPath = McTestkitEnv.envOrNull(McTestkitEnv.RESULT_FILE);
        if (resultPath != null) {
            McTestkitResultWriter writer = new McTestkitResultWriter(new File(resultPath));
            if (pass) {
                writer.pass(message);
            } else {
                writer.fail(message);
            }
        } else {
            // 手工直跑（无编排）兜底：保持旧打印约定。
            System.out.println(pass ? PASS_MARKER : FAIL_PREFIX + message);
        }
    }

    private void shutdownServer() {
        try {
            Class.forName("org.bukkit.Bukkit").getMethod("shutdown").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to stop server", exception);
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
