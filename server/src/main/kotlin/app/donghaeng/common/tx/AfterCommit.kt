package app.donghaeng.common.tx

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 트랜잭션이 커밋된 뒤에만 실행할 부수 효과(Redis 갱신, MQTT 발행)를 등록한다.
 * 트랜잭션 밖에서 불리면 즉시 실행한다. 롤백되면 실행하지 않는다.
 */
fun afterCommit(block: () -> Unit) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = block()
            },
        )
    } else {
        block()
    }
}
