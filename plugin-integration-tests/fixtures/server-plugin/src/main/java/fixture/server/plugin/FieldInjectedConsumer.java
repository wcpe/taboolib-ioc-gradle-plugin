package fixture.server.plugin;

import top.wcpe.taboolib.ioc.annotation.Component;
import top.wcpe.taboolib.ioc.annotation.Inject;

@Component
class FieldInjectedConsumer {

    @Inject
    ConstructorInjectedService service;

    boolean ready() {
        return service != null && "constructor:repository-ready".equals(service.value());
    }
}
