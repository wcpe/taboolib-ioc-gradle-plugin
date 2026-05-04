package fixture.server.plugin;

import top.wcpe.taboolib.ioc.annotation.Component;
import top.wcpe.taboolib.ioc.annotation.Primary;

@Component("alphaGateway")
@Primary
class AlphaGateway implements ServerGateway {

    @Override
    public String channel() {
        return "alpha";
    }
}
