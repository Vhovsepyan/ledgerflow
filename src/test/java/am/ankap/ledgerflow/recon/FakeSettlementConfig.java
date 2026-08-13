package am.ankap.ledgerflow.recon;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FakeSettlementConfig {

    @Bean
    @Primary
    public FakeSettlementSource fakeSettlementSource() {
        return new FakeSettlementSource();
    }
}