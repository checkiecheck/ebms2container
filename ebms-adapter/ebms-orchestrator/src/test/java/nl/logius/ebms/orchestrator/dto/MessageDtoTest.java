package nl.logius.ebms.orchestrator.dto;

import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageDtoTest {

    @Test
    void fromExposesExactPersistedErrorMessageForAdminUi() {
        EbmsMessageEntity entity = EbmsMessageEntity.builder()
            .errorMessage("[CPA_ROLE_MISMATCH] Given role 'Consumer' does not match CPA role 'Sender'")
            .build();

        MessageDto dto = MessageDto.from(entity);

        assertThat(dto.errorMessage()).isEqualTo(
            "[CPA_ROLE_MISMATCH] Given role 'Consumer' does not match CPA role 'Sender'");
    }
}