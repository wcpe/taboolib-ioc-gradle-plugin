package fixture.staticplugin;

import top.wcpe.taboolib.ioc.annotation.Component;
import top.wcpe.taboolib.ioc.annotation.ComponentScan;
import top.wcpe.taboolib.ioc.annotation.Conditional;
import top.wcpe.taboolib.ioc.annotation.ConditionalOnProperty;
import top.wcpe.taboolib.ioc.annotation.Configuration;
import top.wcpe.taboolib.ioc.annotation.Inject;
import top.wcpe.taboolib.ioc.annotation.Named;
import top.wcpe.taboolib.ioc.annotation.Primary;
import top.wcpe.taboolib.ioc.annotation.RefreshScope;
import top.wcpe.taboolib.ioc.annotation.Resource;
import top.wcpe.taboolib.ioc.annotation.ThreadScope;

@Configuration
@ComponentScan(basePackages = {"fixture.staticplugin"})
class StaticDiagnosisFixtureConfiguration {}

@Component class ValidConstructorService0 {}
@Component class ValidConstructorConsumer0 { ValidConstructorConsumer0(ValidConstructorService0 service) {} }
@Component class ValidFieldService0 {}
@Component class ValidFieldConsumer0 { @Inject ValidFieldService0 service; }
@Component class ValidMethodService0 {}
@Component class ValidMethodConsumer0 { @Inject void setService(ValidMethodService0 service) {} }
interface ValidNamedContract0 {}
@Component("validNamedService0") class ValidNamedService0 implements ValidNamedContract0 {}
@Component class ValidNamedConsumer0 { ValidNamedConsumer0(@Named("validNamedService0") ValidNamedContract0 service) {} }
interface ValidPrimaryContract0 {}
@Component @Primary class ValidPrimaryService0 implements ValidPrimaryContract0 {}
@Component class ValidSecondaryService0 implements ValidPrimaryContract0 {}
@Component class ValidPrimaryConsumer0 { ValidPrimaryConsumer0(ValidPrimaryContract0 service) {} }
interface ValidBox0<T> {}
@Component class ValidStringBox0 implements ValidBox0<String> {}
@Component class ValidGenericConsumer0 { ValidGenericConsumer0(ValidBox0<String> box) {} }

@Component class ValidConstructorService1 {}
@Component class ValidConstructorConsumer1 { ValidConstructorConsumer1(ValidConstructorService1 service) {} }
@Component class ValidFieldService1 {}
@Component class ValidFieldConsumer1 { @Inject ValidFieldService1 service; }
@Component class ValidMethodService1 {}
@Component class ValidMethodConsumer1 { @Inject void setService(ValidMethodService1 service) {} }
interface ValidNamedContract1 {}
@Component("validNamedService1") class ValidNamedService1 implements ValidNamedContract1 {}
@Component class ValidNamedConsumer1 { ValidNamedConsumer1(@Named("validNamedService1") ValidNamedContract1 service) {} }
interface ValidPrimaryContract1 {}
@Component @Primary class ValidPrimaryService1 implements ValidPrimaryContract1 {}
@Component class ValidSecondaryService1 implements ValidPrimaryContract1 {}
@Component class ValidPrimaryConsumer1 { ValidPrimaryConsumer1(ValidPrimaryContract1 service) {} }
interface ValidBox1<T> {}
@Component class ValidStringBox1 implements ValidBox1<String> {}
@Component class ValidGenericConsumer1 { ValidGenericConsumer1(ValidBox1<String> box) {} }

@Component class ValidConstructorService2 {}
@Component class ValidConstructorConsumer2 { ValidConstructorConsumer2(ValidConstructorService2 service) {} }
@Component class ValidFieldService2 {}
@Component class ValidFieldConsumer2 { @Inject ValidFieldService2 service; }
@Component class ValidMethodService2 {}
@Component class ValidMethodConsumer2 { @Inject void setService(ValidMethodService2 service) {} }
interface ValidNamedContract2 {}
@Component("validNamedService2") class ValidNamedService2 implements ValidNamedContract2 {}
@Component class ValidNamedConsumer2 { ValidNamedConsumer2(@Named("validNamedService2") ValidNamedContract2 service) {} }
interface ValidPrimaryContract2 {}
@Component @Primary class ValidPrimaryService2 implements ValidPrimaryContract2 {}
@Component class ValidSecondaryService2 implements ValidPrimaryContract2 {}
@Component class ValidPrimaryConsumer2 { ValidPrimaryConsumer2(ValidPrimaryContract2 service) {} }
interface ValidBox2<T> {}
@Component class ValidStringBox2 implements ValidBox2<String> {}
@Component class ValidGenericConsumer2 { ValidGenericConsumer2(ValidBox2<String> box) {} }

@Component class ValidConstructorService3 {}
@Component class ValidConstructorConsumer3 { ValidConstructorConsumer3(ValidConstructorService3 service) {} }
@Component class ValidFieldService3 {}
@Component class ValidFieldConsumer3 { @Inject ValidFieldService3 service; }
@Component class ValidMethodService3 {}
@Component class ValidMethodConsumer3 { @Inject void setService(ValidMethodService3 service) {} }
interface ValidNamedContract3 {}
@Component("validNamedService3") class ValidNamedService3 implements ValidNamedContract3 {}
@Component class ValidNamedConsumer3 { ValidNamedConsumer3(@Named("validNamedService3") ValidNamedContract3 service) {} }
interface ValidPrimaryContract3 {}
@Component @Primary class ValidPrimaryService3 implements ValidPrimaryContract3 {}
@Component class ValidSecondaryService3 implements ValidPrimaryContract3 {}
@Component class ValidPrimaryConsumer3 { ValidPrimaryConsumer3(ValidPrimaryContract3 service) {} }
interface ValidBox3<T> {}
@Component class ValidStringBox3 implements ValidBox3<String> {}
@Component class ValidGenericConsumer3 { ValidGenericConsumer3(ValidBox3<String> box) {} }

@Component class ValidConstructorService4 {}
@Component class ValidConstructorConsumer4 { ValidConstructorConsumer4(ValidConstructorService4 service) {} }
@Component class ValidFieldService4 {}
@Component class ValidFieldConsumer4 { @Inject ValidFieldService4 service; }
@Component class ValidMethodService4 {}
@Component class ValidMethodConsumer4 { @Inject void setService(ValidMethodService4 service) {} }
interface ValidNamedContract4 {}
@Component("validNamedService4") class ValidNamedService4 implements ValidNamedContract4 {}
@Component class ValidNamedConsumer4 { ValidNamedConsumer4(@Named("validNamedService4") ValidNamedContract4 service) {} }
interface ValidPrimaryContract4 {}
@Component @Primary class ValidPrimaryService4 implements ValidPrimaryContract4 {}
@Component class ValidSecondaryService4 implements ValidPrimaryContract4 {}
@Component class ValidPrimaryConsumer4 { ValidPrimaryConsumer4(ValidPrimaryContract4 service) {} }
interface ValidBox4<T> {}
@Component class ValidStringBox4 implements ValidBox4<String> {}
@Component class ValidGenericConsumer4 { ValidGenericConsumer4(ValidBox4<String> box) {} }

@Component class ValidConstructorService5 {}
@Component class ValidConstructorConsumer5 { ValidConstructorConsumer5(ValidConstructorService5 service) {} }
@Component class ValidFieldService5 {}
@Component class ValidFieldConsumer5 { @Inject ValidFieldService5 service; }
@Component class ValidMethodService5 {}
@Component class ValidMethodConsumer5 { @Inject void setService(ValidMethodService5 service) {} }
interface ValidNamedContract5 {}
@Component("validNamedService5") class ValidNamedService5 implements ValidNamedContract5 {}
@Component class ValidNamedConsumer5 { ValidNamedConsumer5(@Named("validNamedService5") ValidNamedContract5 service) {} }
interface ValidPrimaryContract5 {}
@Component @Primary class ValidPrimaryService5 implements ValidPrimaryContract5 {}
@Component class ValidSecondaryService5 implements ValidPrimaryContract5 {}
@Component class ValidPrimaryConsumer5 { ValidPrimaryConsumer5(ValidPrimaryContract5 service) {} }
interface ValidBox5<T> {}
@Component class ValidStringBox5 implements ValidBox5<String> {}
@Component class ValidGenericConsumer5 { ValidGenericConsumer5(ValidBox5<String> box) {} }

@Component class ValidConstructorService6 {}
@Component class ValidConstructorConsumer6 { ValidConstructorConsumer6(ValidConstructorService6 service) {} }
@Component class ValidFieldService6 {}
@Component class ValidFieldConsumer6 { @Inject ValidFieldService6 service; }
@Component class ValidMethodService6 {}
@Component class ValidMethodConsumer6 { @Inject void setService(ValidMethodService6 service) {} }
interface ValidNamedContract6 {}
@Component("validNamedService6") class ValidNamedService6 implements ValidNamedContract6 {}
@Component class ValidNamedConsumer6 { ValidNamedConsumer6(@Named("validNamedService6") ValidNamedContract6 service) {} }
interface ValidPrimaryContract6 {}
@Component @Primary class ValidPrimaryService6 implements ValidPrimaryContract6 {}
@Component class ValidSecondaryService6 implements ValidPrimaryContract6 {}
@Component class ValidPrimaryConsumer6 { ValidPrimaryConsumer6(ValidPrimaryContract6 service) {} }
interface ValidBox6<T> {}
@Component class ValidStringBox6 implements ValidBox6<String> {}
@Component class ValidGenericConsumer6 { ValidGenericConsumer6(ValidBox6<String> box) {} }

@Component class ValidConstructorService7 {}
@Component class ValidConstructorConsumer7 { ValidConstructorConsumer7(ValidConstructorService7 service) {} }
@Component class ValidFieldService7 {}
@Component class ValidFieldConsumer7 { @Inject ValidFieldService7 service; }
@Component class ValidMethodService7 {}
@Component class ValidMethodConsumer7 { @Inject void setService(ValidMethodService7 service) {} }
interface ValidNamedContract7 {}
@Component("validNamedService7") class ValidNamedService7 implements ValidNamedContract7 {}
@Component class ValidNamedConsumer7 { ValidNamedConsumer7(@Named("validNamedService7") ValidNamedContract7 service) {} }
interface ValidPrimaryContract7 {}
@Component @Primary class ValidPrimaryService7 implements ValidPrimaryContract7 {}
@Component class ValidSecondaryService7 implements ValidPrimaryContract7 {}
@Component class ValidPrimaryConsumer7 { ValidPrimaryConsumer7(ValidPrimaryContract7 service) {} }
interface ValidBox7<T> {}
@Component class ValidStringBox7 implements ValidBox7<String> {}
@Component class ValidGenericConsumer7 { ValidGenericConsumer7(ValidBox7<String> box) {} }

@Component class ValidConstructorService8 {}
@Component class ValidConstructorConsumer8 { ValidConstructorConsumer8(ValidConstructorService8 service) {} }
@Component class ValidFieldService8 {}
@Component class ValidFieldConsumer8 { @Inject ValidFieldService8 service; }
@Component class ValidMethodService8 {}
@Component class ValidMethodConsumer8 { @Inject void setService(ValidMethodService8 service) {} }
interface ValidNamedContract8 {}
@Component("validNamedService8") class ValidNamedService8 implements ValidNamedContract8 {}
@Component class ValidNamedConsumer8 { ValidNamedConsumer8(@Named("validNamedService8") ValidNamedContract8 service) {} }
interface ValidPrimaryContract8 {}
@Component @Primary class ValidPrimaryService8 implements ValidPrimaryContract8 {}
@Component class ValidSecondaryService8 implements ValidPrimaryContract8 {}
@Component class ValidPrimaryConsumer8 { ValidPrimaryConsumer8(ValidPrimaryContract8 service) {} }
interface ValidBox8<T> {}
@Component class ValidStringBox8 implements ValidBox8<String> {}
@Component class ValidGenericConsumer8 { ValidGenericConsumer8(ValidBox8<String> box) {} }

@Component class ValidConstructorService9 {}
@Component class ValidConstructorConsumer9 { ValidConstructorConsumer9(ValidConstructorService9 service) {} }
@Component class ValidFieldService9 {}
@Component class ValidFieldConsumer9 { @Inject ValidFieldService9 service; }
@Component class ValidMethodService9 {}
@Component class ValidMethodConsumer9 { @Inject void setService(ValidMethodService9 service) {} }
interface ValidNamedContract9 {}
@Component("validNamedService9") class ValidNamedService9 implements ValidNamedContract9 {}
@Component class ValidNamedConsumer9 { ValidNamedConsumer9(@Named("validNamedService9") ValidNamedContract9 service) {} }
interface ValidPrimaryContract9 {}
@Component @Primary class ValidPrimaryService9 implements ValidPrimaryContract9 {}
@Component class ValidSecondaryService9 implements ValidPrimaryContract9 {}
@Component class ValidPrimaryConsumer9 { ValidPrimaryConsumer9(ValidPrimaryContract9 service) {} }
interface ValidBox9<T> {}
@Component class ValidStringBox9 implements ValidBox9<String> {}
@Component class ValidGenericConsumer9 { ValidGenericConsumer9(ValidBox9<String> box) {} }

interface MissingService0 {}
@Component class MissingBeanConsumer0 { MissingBeanConsumer0(MissingService0 service) {} }
interface NamedMissingContract0 {}
@Component class NamedNotFoundConsumer0 { NamedNotFoundConsumer0(@Named("ghostService0") NamedMissingContract0 service) {} }
interface NamedTypeContract0 {}
class WrongNamedType0 {}
@Component("wrongNamedType0") class WrongNamedTypeBean0 extends WrongNamedType0 {}
@Component class NamedTypeMismatchConsumer0 { @Resource(name = "wrongNamedType0") NamedTypeContract0 service; }
interface MultiplePrimaryContract0 {}
@Component @Primary class MultiplePrimaryOne0 implements MultiplePrimaryContract0 {}
@Component @Primary class MultiplePrimaryTwo0 implements MultiplePrimaryContract0 {}
@Component class MultiplePrimaryConsumer0 { MultiplePrimaryConsumer0(MultiplePrimaryContract0 service) {} }
interface MultipleCandidatesContract0 {}
@Component class MultipleCandidateOne0 implements MultipleCandidatesContract0 {}
@Component class MultipleCandidateTwo0 implements MultipleCandidatesContract0 {}
@Component class MultipleCandidatesConsumer0 { MultipleCandidatesConsumer0(MultipleCandidatesContract0 service) {} }
@Component class MissingInjectComponentService0 {}
class MissingInjectConsumer0 { MissingInjectComponentService0 componentService; void use() { componentService.toString(); } }
@Component class ComponentScanExcludedConsumer0 { ComponentScanExcludedConsumer0(fixture.outside.scan.OutsideScanContract0 service) {} }
interface ConditionalOnlyContract0 {}
@Component @Conditional(DynamicCondition.class) class ConditionalOnlyService0 implements ConditionalOnlyContract0 {}
@Component class ConditionalOnlyConsumer0 { ConditionalOnlyConsumer0(ConditionalOnlyContract0 service) {} }
interface DisabledConditionalContract0 {}
@Component @ConditionalOnProperty(name = "feature.disabled.0", havingValue = "on") class DisabledConditionalService0 implements DisabledConditionalContract0 {}
@Component class DisabledConditionalConsumer0 { DisabledConditionalConsumer0(DisabledConditionalContract0 service) {} }
interface RuntimeOnlyService0 {}
@Component class RuntimeManualConsumer0 { @Inject(required = false) RuntimeOnlyService0 service; }
@Component class CycleA0 { CycleA0(CycleB0 b) {} }
@Component class CycleB0 { CycleB0(CycleA0 a) {} }
@Component @RefreshScope class RefreshResourceBean0 { java.io.InputStream stream; }
@Component @ThreadScope class ThreadScopedBean0 {}

interface MissingService1 {}
@Component class MissingBeanConsumer1 { MissingBeanConsumer1(MissingService1 service) {} }
interface NamedMissingContract1 {}
@Component class NamedNotFoundConsumer1 { NamedNotFoundConsumer1(@Named("ghostService1") NamedMissingContract1 service) {} }
interface NamedTypeContract1 {}
class WrongNamedType1 {}
@Component("wrongNamedType1") class WrongNamedTypeBean1 extends WrongNamedType1 {}
@Component class NamedTypeMismatchConsumer1 { @Resource(name = "wrongNamedType1") NamedTypeContract1 service; }
interface MultiplePrimaryContract1 {}
@Component @Primary class MultiplePrimaryOne1 implements MultiplePrimaryContract1 {}
@Component @Primary class MultiplePrimaryTwo1 implements MultiplePrimaryContract1 {}
@Component class MultiplePrimaryConsumer1 { MultiplePrimaryConsumer1(MultiplePrimaryContract1 service) {} }
interface MultipleCandidatesContract1 {}
@Component class MultipleCandidateOne1 implements MultipleCandidatesContract1 {}
@Component class MultipleCandidateTwo1 implements MultipleCandidatesContract1 {}
@Component class MultipleCandidatesConsumer1 { MultipleCandidatesConsumer1(MultipleCandidatesContract1 service) {} }
@Component class MissingInjectComponentService1 {}
class MissingInjectConsumer1 { MissingInjectComponentService1 componentService; void use() { componentService.toString(); } }
@Component class ComponentScanExcludedConsumer1 { ComponentScanExcludedConsumer1(fixture.outside.scan.OutsideScanContract1 service) {} }
interface ConditionalOnlyContract1 {}
@Component @Conditional(DynamicCondition.class) class ConditionalOnlyService1 implements ConditionalOnlyContract1 {}
@Component class ConditionalOnlyConsumer1 { ConditionalOnlyConsumer1(ConditionalOnlyContract1 service) {} }
interface DisabledConditionalContract1 {}
@Component @ConditionalOnProperty(name = "feature.disabled.1", havingValue = "on") class DisabledConditionalService1 implements DisabledConditionalContract1 {}
@Component class DisabledConditionalConsumer1 { DisabledConditionalConsumer1(DisabledConditionalContract1 service) {} }
interface RuntimeOnlyService1 {}
@Component class RuntimeManualConsumer1 { @Inject(required = false) RuntimeOnlyService1 service; }
@Component class CycleA1 { CycleA1(CycleB1 b) {} }
@Component class CycleB1 { CycleB1(CycleA1 a) {} }
@Component @RefreshScope class RefreshResourceBean1 { java.io.InputStream stream; }
@Component @ThreadScope class ThreadScopedBean1 {}

interface MissingService2 {}
@Component class MissingBeanConsumer2 { MissingBeanConsumer2(MissingService2 service) {} }
interface NamedMissingContract2 {}
@Component class NamedNotFoundConsumer2 { NamedNotFoundConsumer2(@Named("ghostService2") NamedMissingContract2 service) {} }
interface NamedTypeContract2 {}
class WrongNamedType2 {}
@Component("wrongNamedType2") class WrongNamedTypeBean2 extends WrongNamedType2 {}
@Component class NamedTypeMismatchConsumer2 { @Resource(name = "wrongNamedType2") NamedTypeContract2 service; }
interface MultiplePrimaryContract2 {}
@Component @Primary class MultiplePrimaryOne2 implements MultiplePrimaryContract2 {}
@Component @Primary class MultiplePrimaryTwo2 implements MultiplePrimaryContract2 {}
@Component class MultiplePrimaryConsumer2 { MultiplePrimaryConsumer2(MultiplePrimaryContract2 service) {} }
interface MultipleCandidatesContract2 {}
@Component class MultipleCandidateOne2 implements MultipleCandidatesContract2 {}
@Component class MultipleCandidateTwo2 implements MultipleCandidatesContract2 {}
@Component class MultipleCandidatesConsumer2 { MultipleCandidatesConsumer2(MultipleCandidatesContract2 service) {} }
@Component class MissingInjectComponentService2 {}
class MissingInjectConsumer2 { MissingInjectComponentService2 componentService; void use() { componentService.toString(); } }
@Component class ComponentScanExcludedConsumer2 { ComponentScanExcludedConsumer2(fixture.outside.scan.OutsideScanContract2 service) {} }
interface ConditionalOnlyContract2 {}
@Component @Conditional(DynamicCondition.class) class ConditionalOnlyService2 implements ConditionalOnlyContract2 {}
@Component class ConditionalOnlyConsumer2 { ConditionalOnlyConsumer2(ConditionalOnlyContract2 service) {} }
interface DisabledConditionalContract2 {}
@Component @ConditionalOnProperty(name = "feature.disabled.2", havingValue = "on") class DisabledConditionalService2 implements DisabledConditionalContract2 {}
@Component class DisabledConditionalConsumer2 { DisabledConditionalConsumer2(DisabledConditionalContract2 service) {} }
interface RuntimeOnlyService2 {}
@Component class RuntimeManualConsumer2 { @Inject(required = false) RuntimeOnlyService2 service; }
@Component class CycleA2 { CycleA2(CycleB2 b) {} }
@Component class CycleB2 { CycleB2(CycleA2 a) {} }
@Component @RefreshScope class RefreshResourceBean2 { java.io.InputStream stream; }
@Component @ThreadScope class ThreadScopedBean2 {}

interface MissingService3 {}
@Component class MissingBeanConsumer3 { MissingBeanConsumer3(MissingService3 service) {} }
interface NamedMissingContract3 {}
@Component class NamedNotFoundConsumer3 { NamedNotFoundConsumer3(@Named("ghostService3") NamedMissingContract3 service) {} }
interface NamedTypeContract3 {}
class WrongNamedType3 {}
@Component("wrongNamedType3") class WrongNamedTypeBean3 extends WrongNamedType3 {}
@Component class NamedTypeMismatchConsumer3 { @Resource(name = "wrongNamedType3") NamedTypeContract3 service; }
interface MultiplePrimaryContract3 {}
@Component @Primary class MultiplePrimaryOne3 implements MultiplePrimaryContract3 {}
@Component @Primary class MultiplePrimaryTwo3 implements MultiplePrimaryContract3 {}
@Component class MultiplePrimaryConsumer3 { MultiplePrimaryConsumer3(MultiplePrimaryContract3 service) {} }
interface MultipleCandidatesContract3 {}
@Component class MultipleCandidateOne3 implements MultipleCandidatesContract3 {}
@Component class MultipleCandidateTwo3 implements MultipleCandidatesContract3 {}
@Component class MultipleCandidatesConsumer3 { MultipleCandidatesConsumer3(MultipleCandidatesContract3 service) {} }
@Component class MissingInjectComponentService3 {}
class MissingInjectConsumer3 { MissingInjectComponentService3 componentService; void use() { componentService.toString(); } }
@Component class ComponentScanExcludedConsumer3 { ComponentScanExcludedConsumer3(fixture.outside.scan.OutsideScanContract3 service) {} }
interface ConditionalOnlyContract3 {}
@Component @Conditional(DynamicCondition.class) class ConditionalOnlyService3 implements ConditionalOnlyContract3 {}
@Component class ConditionalOnlyConsumer3 { ConditionalOnlyConsumer3(ConditionalOnlyContract3 service) {} }
interface DisabledConditionalContract3 {}
@Component @ConditionalOnProperty(name = "feature.disabled.3", havingValue = "on") class DisabledConditionalService3 implements DisabledConditionalContract3 {}
@Component class DisabledConditionalConsumer3 { DisabledConditionalConsumer3(DisabledConditionalContract3 service) {} }
interface RuntimeOnlyService3 {}
@Component class RuntimeManualConsumer3 { @Inject(required = false) RuntimeOnlyService3 service; }
@Component class CycleA3 { CycleA3(CycleB3 b) {} }
@Component class CycleB3 { CycleB3(CycleA3 a) {} }
@Component @RefreshScope class RefreshResourceBean3 { java.io.InputStream stream; }
@Component @ThreadScope class ThreadScopedBean3 {}

interface MissingService4 {}
@Component class MissingBeanConsumer4 { MissingBeanConsumer4(MissingService4 service) {} }
interface NamedMissingContract4 {}
@Component class NamedNotFoundConsumer4 { NamedNotFoundConsumer4(@Named("ghostService4") NamedMissingContract4 service) {} }
interface NamedTypeContract4 {}
class WrongNamedType4 {}
@Component("wrongNamedType4") class WrongNamedTypeBean4 extends WrongNamedType4 {}
@Component class NamedTypeMismatchConsumer4 { @Resource(name = "wrongNamedType4") NamedTypeContract4 service; }
interface MultiplePrimaryContract4 {}
@Component @Primary class MultiplePrimaryOne4 implements MultiplePrimaryContract4 {}
@Component @Primary class MultiplePrimaryTwo4 implements MultiplePrimaryContract4 {}
@Component class MultiplePrimaryConsumer4 { MultiplePrimaryConsumer4(MultiplePrimaryContract4 service) {} }
interface MultipleCandidatesContract4 {}
@Component class MultipleCandidateOne4 implements MultipleCandidatesContract4 {}
@Component class MultipleCandidateTwo4 implements MultipleCandidatesContract4 {}
@Component class MultipleCandidatesConsumer4 { MultipleCandidatesConsumer4(MultipleCandidatesContract4 service) {} }
@Component class MissingInjectComponentService4 {}
class MissingInjectConsumer4 { MissingInjectComponentService4 componentService; void use() { componentService.toString(); } }
@Component class ComponentScanExcludedConsumer4 { ComponentScanExcludedConsumer4(fixture.outside.scan.OutsideScanContract4 service) {} }
interface ConditionalOnlyContract4 {}
@Component @Conditional(DynamicCondition.class) class ConditionalOnlyService4 implements ConditionalOnlyContract4 {}
@Component class ConditionalOnlyConsumer4 { ConditionalOnlyConsumer4(ConditionalOnlyContract4 service) {} }
interface DisabledConditionalContract4 {}
@Component @ConditionalOnProperty(name = "feature.disabled.4", havingValue = "on") class DisabledConditionalService4 implements DisabledConditionalContract4 {}
@Component class DisabledConditionalConsumer4 { DisabledConditionalConsumer4(DisabledConditionalContract4 service) {} }
interface RuntimeOnlyService4 {}
@Component class RuntimeManualConsumer4 { @Inject(required = false) RuntimeOnlyService4 service; }
@Component class CycleA4 { CycleA4(CycleB4 b) {} }
@Component class CycleB4 { CycleB4(CycleA4 a) {} }
@Component @RefreshScope class RefreshResourceBean4 { java.io.InputStream stream; }
@Component @ThreadScope class ThreadScopedBean4 {}

