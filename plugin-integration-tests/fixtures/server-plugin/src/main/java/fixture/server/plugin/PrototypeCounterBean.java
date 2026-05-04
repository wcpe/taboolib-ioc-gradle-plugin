package fixture.server.plugin;

import java.util.concurrent.atomic.AtomicInteger;
import top.wcpe.taboolib.ioc.annotation.Component;
import top.wcpe.taboolib.ioc.annotation.Prototype;

@Component
@Prototype
class PrototypeCounterBean {

    static final AtomicInteger constructed = new AtomicInteger();

    final int id = constructed.incrementAndGet();
}
