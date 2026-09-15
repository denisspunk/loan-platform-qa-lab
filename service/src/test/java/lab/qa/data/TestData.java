package lab.qa.data;

import lab.qa.clients.LoanRequest;

import java.util.Random;
import java.util.UUID;

/** Test data owned by each test: unique ids, sensible defaults, change only what matters. */
public final class TestData {

    private static final Random RANDOM = new Random();

    private TestData() {
    }

    /** A 15-digit IMEI-like id, new for every call, so two tests never share a phone. */
    public static String uniqueImei() {
        StringBuilder imei = new StringBuilder("35");
        for (int i = 0; i < 13; i++) {
            imei.append(RANDOM.nextInt(10));
        }
        return imei.toString();
    }

    /** A payment id in the M-Pesa style, new for every call, so no test's payment looks like a duplicate. */
    public static String uniquePaymentId() {
        return "MPESA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    public static LoanBuilder aLoan() {
        return new LoanBuilder();
    }

    /** aLoan().price(300).dailyRate(100).build(): defaults for everything else. */
    public static final class LoanBuilder {

        private String deviceId = uniqueImei();
        private long price = 12_000;
        private long dailyRate = 100;

        public LoanBuilder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public LoanBuilder price(long price) {
            this.price = price;
            return this;
        }

        public LoanBuilder dailyRate(long dailyRate) {
            this.dailyRate = dailyRate;
            return this;
        }

        public LoanRequest build() {
            return new LoanRequest(deviceId, price, dailyRate);
        }
    }
}
