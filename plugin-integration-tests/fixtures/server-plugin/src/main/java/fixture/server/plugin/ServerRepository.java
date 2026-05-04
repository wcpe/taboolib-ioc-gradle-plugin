package fixture.server.plugin;

import top.wcpe.taboolib.ioc.annotation.Component;

@Component
class ServerRepository {

    String value() {
        return "repository-ready";
    }
}
