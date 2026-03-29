package org.borysovski.eventsourcing.projection;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "account_projections")
public class AccountProjection {

    @Id
    private String accountId;
    private String owner;
    private BigDecimal balance;
    private long version;
    private Instant lastUpdated;
    private List<EventSummary> recentEvents = new ArrayList<>();

    public AccountProjection() {}

    public AccountProjection(String accountId, String owner, BigDecimal balance) {
        this.accountId = accountId;
        this.owner = owner;
        this.balance = balance;
        this.lastUpdated = Instant.now();
    }

    public record EventSummary(String type, BigDecimal amount, String description, Instant occurredAt) {}

    // Getters and setters
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }

    public List<EventSummary> getRecentEvents() { return recentEvents; }
    public void setRecentEvents(List<EventSummary> recentEvents) { this.recentEvents = recentEvents; }
}
