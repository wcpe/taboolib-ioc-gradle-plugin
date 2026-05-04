package fixture.staticplugin;

import top.wcpe.taboolib.ioc.annotation.Condition;
import top.wcpe.taboolib.ioc.annotation.ConditionContext;

public class DynamicCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        return Boolean.getBoolean("fixture.dynamic.condition");
    }
}
