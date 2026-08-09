package am.ankap.ledgerflow.psp;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FakePspConfig {

    @Bean
    @Primary
    public FakePspService fakePspService() {
        return new FakePspService();
    }
}