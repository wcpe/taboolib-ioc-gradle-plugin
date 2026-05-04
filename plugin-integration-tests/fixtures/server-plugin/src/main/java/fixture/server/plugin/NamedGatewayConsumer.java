package fixture.server.plugin;

import top.wcpe.taboolib.ioc.annotation.Component;
import top.wcpe.taboolib.ioc.annotation.Inject;
import top.wcpe.taboolib.ioc.annotation.Named;

@Component
class NamedGatewayConsumer {

    @Inject
    @Named("betaGateway")
    ServerGateway gateway;

    boolean ready() {
        return gateway != null && "beta".equals(gateway.channel());
    }
}
