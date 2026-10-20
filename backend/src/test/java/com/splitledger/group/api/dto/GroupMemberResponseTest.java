package com.splitledger.group.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroupMemberResponseTest {

    @Test
    void exposesNameAndRoleOnlyNeverContactOrPaymentDetails() {
        List<String> components = Arrays.stream(GroupMemberResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // Adding someone to a group must not broadcast their address book entry, and a
        // payment address goes only to its owner and a settlement counterparty (D-36).
        // A component that does not exist cannot be leaked by a careless mapper.
        assertThat(components).containsExactlyInAnyOrder("userId", "name", "role", "joinedAt");
        assertThat(components).doesNotContain("email", "upiId", "passwordHash");
    }
}
