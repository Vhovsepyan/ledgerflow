package am.ankap.ledgerflow.payment;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

public final class MerchantFixture {

    private MerchantFixture() {
    }

    public static UUID createMerchant(JdbcClient jdbcClient) {
        UUID merchantId = UUID.randomUUID();
        jdbcClient.sql("insert into merchant (id, name) values (:id, :name)")
                .param("id", merchantId)
                .param("name", "Merchant " + merchantId)
                .update();
        return merchantId;
    }
}