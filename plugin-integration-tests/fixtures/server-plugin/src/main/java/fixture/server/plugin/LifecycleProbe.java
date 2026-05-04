package fixture.server.plugin;

import top.wcpe.taboolib.ioc.annotation.Component;
import top.wcpe.taboolib.ioc.annotation.Inject;
import top.wcpe.taboolib.ioc.annotation.PostConstruct;

@Component
class LifecycleProbe {

    static boolean initialized = false;

    @Inject
    ServerRepository repository;

    @PostConstruct
    void onInit() {
        initialized = repository != null && "repository-ready".equals(repository.value());
    }
}
