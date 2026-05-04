package fixture.server.plugin;

import top.wcpe.taboolib.ioc.annotation.Component;
import top.wcpe.taboolib.ioc.annotation.Inject;

@Component
class ConstructorInjectedService {

    private final ServerRepository repository;

    @Inject
    ConstructorInjectedService(ServerRepository repository) {
        this.repository = repository;
    }

    String value() {
        return "constructor:" + repository.value();
    }
}
