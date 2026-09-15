package lab.qa.dsl;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.restassured.path.json.JsonPath;
import lab.qa.clients.KnoxStub;
import lab.qa.clients.LoanJson;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the device-lock partner was asked to do. Each check returns this, so checks chain. */
public class DeviceSteps {

    private final KnoxStub knox;

    public DeviceSteps(KnoxStub knox) {
        this.knox = knox;
    }

    public DeviceSteps shouldHaveUnlocked(LoanJson loan, int times) {
        assertThat(knox.callsFor(loan.deviceId(), "unlock"))
                .as("unlock calls for device %s", loan.deviceId())
                .hasSize(times);
        return this;
    }

    public DeviceSteps shouldHaveScheduledRelockAt(LoanJson loan, String isoInstant) {
        List<LoggedRequest> relocks = knox.callsFor(loan.deviceId(), "relock");
        assertThat(relocks)
                .as("relock calls for device %s", loan.deviceId())
                .isNotEmpty();
        String lastBody = relocks.get(relocks.size() - 1).getBodyAsString();
        assertThat(JsonPath.from(lastBody).getString("relockAt"))
                .as("relockAt sent to the partner")
                .isEqualTo(isoInstant);
        return this;
    }

    public DeviceSteps shouldHaveReleased(LoanJson loan) {
        assertThat(knox.callsFor(loan.deviceId(), "release"))
                .as("release calls for device %s", loan.deviceId())
                .hasSize(1);
        return this;
    }

    public DeviceSteps shouldNotHaveContactedThePartner(LoanJson loan) {
        assertThat(knox.callsFor(loan.deviceId(), "unlock")).as("unlock calls").isEmpty();
        assertThat(knox.callsFor(loan.deviceId(), "relock")).as("relock calls").isEmpty();
        assertThat(knox.callsFor(loan.deviceId(), "release")).as("release calls").isEmpty();
        return this;
    }
}
