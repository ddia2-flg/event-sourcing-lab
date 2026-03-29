package org.borysovski.eventsourcing.projection;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountProjectionRepository extends MongoRepository<AccountProjection, String> {}
