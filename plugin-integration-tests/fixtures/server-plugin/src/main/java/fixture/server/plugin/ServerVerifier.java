package fixture.server.plugin;

import java.util.List;
import top.wcpe.taboolib.ioc.annotation.Component;
import top.wcpe.taboolib.ioc.annotation.Inject;
import top.wcpe.taboolib.ioc.annotation.PostEnable;
import top.wcpe.taboolib.ioc.bean.BeanContainer;

@Component
class ServerVerifier {

    @Inject
    FieldInjectedConsumer fieldConsumer;

    @Inject
    NamedGatewayConsumer namedConsumer;

    @PostEnable
    void verify() {
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

        System.out.println("[IoC-Server-Test] PASS");
        shutdownServer();
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
            System.out.println("[IoC-Server-Test] FAIL: " + message);
            throw new IllegalStateException(message);
        }
    }
}
