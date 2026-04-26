package cz.solvina.options.domain.features.account

import kotlinx.coroutines.flow.StateFlow

interface AccountPort {
    val accountDetail: StateFlow<AccountDetail?>
}
