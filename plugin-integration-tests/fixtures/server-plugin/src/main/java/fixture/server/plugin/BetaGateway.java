package fixture.server.plugin;

import top.wcpe.taboolib.ioc.annotation.Component;

@Component("betaGateway")
class BetaGateway implements ServerGateway {

    @Override
    public String channel() {
        return "beta";
    }
}
