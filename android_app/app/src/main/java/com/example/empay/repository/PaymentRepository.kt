package com.example.empay.repository

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.example.empay.auth.SessionManager
import com.example.empay.auth.UserSession
import com.example.empay.bluetooth.AckPacket
import com.example.empay.bluetooth.BluetoothPaymentEngine
import com.example.empay.bluetooth.PaymentPacket
import com.example.empay.db.AppDatabase
import com.example.empay.db.TransactionEntity
import com.example.empay.db.WalletEntity
import com.example.empay.network.BankApiClient
import com.example.empay.network.BankApiService
import com.example.empay.network.BankUserResponse
import com.example.empay.network.NetworkManager
import com.example.empay.network.SyncTransactionItem
import com.example.empay.network.SyncTransactionsRequest
import com.example.empay.network.SyncTransactionsResponse
import com.example.empay.network.TransferEmergencyRequest
import com.example.empay.network.TransferEmergencyResponse
import com.example.empay.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 7: Unified Domain Repository & Clean Architecture Facade.
 *
 * Coordinates between UI controllers, Central Bank REST API, SQLite local ledger,
 * Bluetooth P2P RFCOMM engine, Android WorkManager, and hardware Keystore crypto.
 */
class PaymentRepository private constructor(private val context: Context) {

    private val appDatabase: AppDatabase = AppDatabase.getInstance(context)
    private val sessionManager: SessionManager = SessionManager.getInstance(context)
    private val bluetoothEngine: BluetoothPaymentEngine = BluetoothPaymentEngine(context)

    companion object {
        @Volatile
        private var INSTANCE: PaymentRepository? = null

        fun getInstance(context: Context): PaymentRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PaymentRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun getApiService(): BankApiService {
        return BankApiClient.getApiService(context)
    }

    /**
     * Observes active user session (balances, account, online state).
     */
    fun observeUserSession(): StateFlow<UserSession> = sessionManager.sessionState

    fun getCurrentUser(): UserSession = sessionManager.getUser()

    /**
     * Rapidly fetches authoritative balances from Central Bank server when online,
     * while preserving local SQLite offline deductions for pending unsynced payments.
     */
    suspend fun refreshAuthoritativeBalances(): UserSession = withContext(Dispatchers.IO) {
        val user = sessionManager.getUser()
        val localWallet = appDatabase.walletDao.getWallet()
        val localEmergency = localWallet?.balance ?: user.emergencyBalance

        if (!NetworkManager.isOnline(context)) {
            val updated = user.copy(emergencyBalance = localEmergency)
            sessionManager.updateUser(updated)
            return@withContext updated
        }

        try {
            val api = getApiService()
            val resp = try {
                val phoneRes = api.getUserByPhone(user.phone)
                if (phoneRes.isSuccessful && phoneRes.body() != null) phoneRes else api.getUserById(user.id)
            } catch (_: Exception) {
                api.getUserById(user.id)
            }

            if (resp.isSuccessful && resp.body() != null) {
                val remote = resp.body()!!
                val pending = appDatabase.transactionDao.getPendingSyncTransactions()
                val effectiveEmergency = if (pending.isNotEmpty()) {
                    localEmergency
                } else {
                    appDatabase.walletDao.updateBalance(remote.emergencyBalance)
                    remote.emergencyBalance
                }

                val updated = user.copy(
                    id = remote.id,
                    name = remote.name,
                    phone = remote.phone,
                    balance = remote.balance,
                    emergencyBalance = effectiveEmergency
                )
                sessionManager.updateUser(updated)
                return@withContext updated
            }
        } catch (e: Exception) {
            // Retain local balances on network hiccup
        }

        val updated = user.copy(emergencyBalance = localEmergency)
        sessionManager.updateUser(updated)
        return@withContext updated
    }

    suspend fun switchActiveUser(user: UserSession): UserSession = withContext(Dispatchers.IO) {
        sessionManager.updateUser(user)
        appDatabase.walletDao.updateBalance(user.emergencyBalance)

        if (NetworkManager.isOnline(context)) {
            try {
                val api = getApiService()
                val resp = try {
                    val phoneRes = api.getUserByPhone(user.phone)
                    if (phoneRes.isSuccessful && phoneRes.body() != null) phoneRes else api.getUserById(user.id)
                } catch (_: Exception) {
                    api.getUserById(user.id)
                }
                if (resp.isSuccessful && resp.body() != null) {
                    val remote = resp.body()!!
                    val updated = user.copy(
                        id = remote.id,
                        name = remote.name,
                        phone = remote.phone,
                        balance = remote.balance,
                        emergencyBalance = remote.emergencyBalance
                    )
                    sessionManager.updateUser(updated)
                    appDatabase.walletDao.updateBalance(remote.emergencyBalance)
                    return@withContext updated
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext user
    }

    suspend fun fetchAllBankUsers(): List<BankUserResponse> = withContext(Dispatchers.IO) {
        if (!NetworkManager.isOnline(context)) return@withContext emptyList()
        try {
            val resp = getApiService().getAllUsers()
            if (resp.isSuccessful && resp.body() != null) {
                return@withContext resp.body()!!
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext emptyList()
    }

    suspend fun getOfflineWallet(): WalletEntity? = withContext(Dispatchers.IO) {
        appDatabase.walletDao.getWallet()
    }

    suspend fun getAllTransactions(): List<TransactionEntity> = withContext(Dispatchers.IO) {
        appDatabase.transactionDao.getAllTransactions()
    }

    suspend fun getPendingSyncCount(): Int = withContext(Dispatchers.IO) {
        appDatabase.transactionDao.getPendingSyncTransactions().size
    }

    /**
     * Reserves emergency funds from online bank account into local offline wallet.
     * Enforces active connection to Central Bank and atomic debit/credit invariants.
     */
    suspend fun reserveEmergencyFunds(amount: Double): Result<TransferEmergencyResponse> = withContext(Dispatchers.IO) {
        if (!NetworkManager.isOnline(context)) {
            return@withContext Result.failure(
                IllegalStateException("Device is offline. Online connection to Central Bank required to reserve funds.")
            )
        }

        if (amount <= 0.0) {
            return@withContext Result.failure(
                IllegalArgumentException("Amount must be greater than zero.")
            )
        }

        try {
            val apiService = getApiService()
            val request = TransferEmergencyRequest(userId = sessionManager.getUser().id, amount = amount)
            val response = apiService.transferToEmergency(request)

            if (response.isSuccessful && response.body() != null) {
                val result = response.body()!!
                if (result.success) {
                    appDatabase.walletDao.addBalance(amount)
                    sessionManager.updateBalances(result.balance, result.emergencyBalance)
                    return@withContext Result.success(result)
                } else {
                    return@withContext Result.failure(Exception(result.message))
                }
            } else {
                val errCode = response.code()
                val errMsg = response.errorBody()?.string() ?: "Unknown bank error"
                return@withContext Result.failure(Exception("HTTP $errCode: $errMsg"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    /**
     * Synchronizes all local PENDING_SYNC receipts with the Central Bank ledger.
     * When pending items exist, sends batch reconciliation and updates balances permanently.
     * When empty, refreshes latest authoritative user balances from bank.
     */
    suspend fun syncPendingTransactions(): Result<SyncTransactionsResponse> = withContext(Dispatchers.IO) {
        if (!NetworkManager.isOnline(context)) {
            return@withContext Result.failure(
                IllegalStateException("Cannot synchronize: No network connection to Central Bank.")
            )
        }

        try {
            val apiService = getApiService()
            val pendingTxs = appDatabase.transactionDao.getPendingSyncTransactions()

            if (pendingTxs.isNotEmpty()) {
                val syncItems = pendingTxs.map {
                    SyncTransactionItem(
                        txId = it.txId,
                        payee = it.receiverName,
                        amount = it.amount,
                        note = "Bluetooth Offline Payment (Direction: ${it.direction})",
                        direction = it.direction,
                        senderId = it.senderId,
                        receiverId = it.receiverId
                    )
                }

                val currentUserId = sessionManager.getUser().id
                val syncResponse = apiService.syncTransactions(
                    SyncTransactionsRequest(transactions = syncItems, userId = currentUserId)
                )
                if (syncResponse.isSuccessful && syncResponse.body() != null) {
                    val body = syncResponse.body()!!
                    pendingTxs.forEach { tx ->
                        appDatabase.transactionDao.markAsSynced(tx.txId)
                    }
                    appDatabase.walletDao.updateBalance(body.emergencyBalance)
                    sessionManager.updateBalances(body.balance, body.emergencyBalance)
                    return@withContext Result.success(body)
                } else {
                    return@withContext Result.failure(
                        Exception("HTTP ${syncResponse.code()}: ${syncResponse.errorBody()?.string() ?: "Sync failed"}")
                    )
                }
            } else {
                val currentUser = sessionManager.getUser()
                val userResponse = try {
                    val phoneRes = apiService.getUserByPhone(currentUser.phone)
                    if (phoneRes.isSuccessful && phoneRes.body() != null) phoneRes else apiService.getUserById(currentUser.id)
                } catch (_: Exception) {
                    apiService.getUserById(currentUser.id)
                }
                if (userResponse.isSuccessful && userResponse.body() != null) {
                    val bankUser = userResponse.body()!!
                    val updated = currentUser.copy(
                        id = bankUser.id,
                        name = bankUser.name,
                        phone = bankUser.phone,
                        balance = bankUser.balance,
                        emergencyBalance = bankUser.emergencyBalance
                    )
                    sessionManager.updateUser(updated)
                    appDatabase.walletDao.updateBalance(bankUser.emergencyBalance)
                    return@withContext Result.success(
                        SyncTransactionsResponse(
                            status = "success",
                            message = "Authoritative balances refreshed from bank.",
                            reconciledCount = 0,
                            balance = bankUser.balance,
                            emergencyBalance = bankUser.emergencyBalance
                        )
                    )
                } else {
                    return@withContext Result.failure(
                        Exception("HTTP ${userResponse.code()}: Unable to refresh account profile")
                    )
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    /**
     * Executes single-device evaluator loopback payment.
     * Deducts payer wallet, logs PENDING_SYNC, and auto-dispatches WorkManager sync if online.
     */
    fun executeLoopbackPayment(
        receiverId: String,
        receiverName: String,
        amount: Double,
        onStatusLog: (String) -> Unit,
        onSuccess: (PaymentPacket, AckPacket) -> Unit,
        onError: (String) -> Unit
    ) {
        bluetoothEngine.simulateP2PLoopbackPayment(
            receiverId = receiverId,
            receiverName = receiverName,
            amount = amount,
            onStatusLog = onStatusLog,
            onSuccess = { packet, ack ->
                if (NetworkManager.isOnline(context)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        SyncManager.syncNow(context)
                    }
                }
                onSuccess(packet, ack)
            },
            onError = onError
        )
    }

    /**
     * Executes authentic Bluetooth RFCOMM P2P payment to paired peer device.
     */
    fun executeBluetoothPayment(
        device: BluetoothDevice,
        receiverId: String,
        receiverName: String,
        amount: Double,
        onStatusLog: (String) -> Unit,
        onSuccess: (PaymentPacket, AckPacket) -> Unit,
        onError: (String) -> Unit
    ) {
        bluetoothEngine.sendPaymentToDevice(
            device = device,
            receiverId = receiverId,
            receiverName = receiverName,
            amount = amount,
            onStatusLog = onStatusLog,
            onSuccess = { packet, ack ->
                if (NetworkManager.isOnline(context)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        SyncManager.syncNow(context)
                    }
                }
                onSuccess(packet, ack)
            },
            onError = onError
        )
    }

    fun startMerchantRadar(
        onPaymentReceived: (PaymentPacket, AckPacket) -> Unit,
        onStatusLog: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        bluetoothEngine.startMerchantServer(onPaymentReceived, onStatusLog, onError)
    }

    fun stopMerchantRadar() {
        bluetoothEngine.stopMerchantServer()
    }

    fun getPairedBluetoothDevices(): List<BluetoothDevice> {
        return bluetoothEngine.getPairedDevices()
    }

    fun triggerBackgroundSync() {
        SyncManager.triggerImmediateSync(context)
    }
}
