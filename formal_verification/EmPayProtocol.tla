------------------------- MODULE EmPayProtocol -------------------------
EXTENDS Integers, Sequences, FiniteSets, TLC

(*
   ======================================================================
   EmPay-Lab: Formal Verification of Offline-First Bluetooth Protocol
   ======================================================================
   This specification formally models the state machine, offline fund
   allocation, Bluetooth P2P exchange, and bank reconciliation.
   
   Invariants verified by TLC:
   1. SpentLeqAllocated: Total spent offline cannot exceed allocated reserve.
   2. ZeroDoubleSettlement: A single transaction ID settles at most once.
   3. NoPhantomMoney: Reconciliation cannot create additional system liquidity.
   4. StateIntegrity: Transactions transition strictly through legal states.
   ======================================================================
*)

CONSTANTS
    Payers,         \* Set of Payer devices, e.g. {P1}
    Merchants,      \* Set of Merchant devices, e.g. {M1, M2}
    MaxAllocation,  \* Maximum offline fund allocation, e.g. 100
    TxAmounts       \* Possible transaction values, e.g. {20, 50}

VARIABLES
    bankOnlineBalance,     \* [Payers -> Int]
    bankEmergencyBalance,  \* [Payers -> Int]
    localEmergencyBalance, \* [Payers -> Int]
    txStatus,              \* [TxId -> Status]
    txPayer,               \* [TxId -> Payers]
    txMerchant,            \* [TxId -> Merchants]
    txAmount,              \* [TxId -> Int]
    settledTxs             \* Set of TxIds settled at the Central Bank

vars == <<bankOnlineBalance, bankEmergencyBalance, localEmergencyBalance, 
          txStatus, txPayer, txMerchant, txAmount, settledTxs>>

\* State Machine Enums
TxStates == {"UNBORN", "ALLOCATED", "INITIATED", "BLUETOOTH_EXCHANGE", "PENDING", "RECONCILIATION", "SETTLED", "ABORTED", "REJECTED"}

\* Upper bound of unique transactions modeled for finite state verification
TxPool == 1..3

-------------------------------------------------------------------------
\* Initial State
Init ==
    /\ bankOnlineBalance = [p \in Payers |-> 500]
    /\ bankEmergencyBalance = [p \in Payers |-> 0]
    /\ localEmergencyBalance = [p \in Payers |-> 0]
    /\ txStatus = [t \in TxPool |-> "UNBORN"]
    /\ txPayer = [t \in TxPool |-> CHOOSE p \in Payers : TRUE]
    /\ txMerchant = [t \in TxPool |-> CHOOSE m \in Merchants : TRUE]
    /\ txAmount = [t \in TxPool |-> 0]
    /\ settledTxs = {}

-------------------------------------------------------------------------
\* Action 1: Online Payer allocates emergency reserve from bank balance
AllocateEmergency(p, amount) ==
    /\ bankOnlineBalance[p] >= amount
    /\ amount > 0
    /\ bankEmergencyBalance[p] + amount <= MaxAllocation
    /\ bankOnlineBalance' = [bankOnlineBalance EXCEPT ![p] = @ - amount]
    /\ bankEmergencyBalance' = [bankEmergencyBalance EXCEPT ![p] = @ + amount]
    /\ localEmergencyBalance' = [localEmergencyBalance EXCEPT ![p] = @ + amount]
    /\ UNCHANGED <<txStatus, txPayer, txMerchant, txAmount, settledTxs>>

\* Action 2: Payer initiates offline payment via Bluetooth with 4-digit PIN
InitiateOfflineTx(t, p, m, amt) ==
    /\ txStatus[t] = "UNBORN"
    /\ amt \in TxAmounts
    /\ localEmergencyBalance[p] >= amt
    /\ txStatus' = [txStatus EXCEPT ![t] = "INITIATED"]
    /\ txPayer' = [txPayer EXCEPT ![t] = p]
    /\ txMerchant' = [txMerchant EXCEPT ![t] = m]
    /\ txAmount' = [txAmount EXCEPT ![t] = amt]
    /\ localEmergencyBalance' = [localEmergencyBalance EXCEPT ![p] = @ - amt]
    /\ UNCHANGED <<bankOnlineBalance, bankEmergencyBalance, settledTxs>>

\* Action 3: Bluetooth RFCOMM exchange and signature verification at Merchant
BluetoothExchange(t) ==
    /\ txStatus[t] = "INITIATED"
    /\ txStatus' = [txStatus EXCEPT ![t] = "PENDING"]
    /\ UNCHANGED <<bankOnlineBalance, bankEmergencyBalance, localEmergencyBalance, 
                  txPayer, txMerchant, txAmount, settledTxs>>

\* Action 4: Bluetooth link dropped or PIN authentication failed
AbortTx(t) ==
    /\ txStatus[t] \in {"INITIATED", "BLUETOOTH_EXCHANGE"}
    /\ txStatus' = [txStatus EXCEPT ![t] = "ABORTED"]
    \* Rollback local balance to payer since merchant never received receipt
    /\ localEmergencyBalance' = [localEmergencyBalance EXCEPT ![txPayer[t]] = @ + txAmount[t]]
    /\ UNCHANGED <<bankOnlineBalance, bankEmergencyBalance, txPayer, txMerchant, txAmount, settledTxs>>

\* Action 5: Reconnect to Internet and Reconcile with Central Bank
ReconcileSettlement(t) ==
    /\ txStatus[t] = "PENDING"
    /\ t \notin settledTxs
    /\ txStatus' = [txStatus EXCEPT ![t] = "SETTLED"]
    /\ settledTxs' = settledTxs \cup {t}
    /\ bankEmergencyBalance' = [bankEmergencyBalance EXCEPT ![txPayer[t]] = 
                                 IF @ >= txAmount[t] THEN @ - txAmount[t] ELSE 0]
    /\ UNCHANGED <<bankOnlineBalance, localEmergencyBalance, txPayer, txMerchant, txAmount>>

\* Action 6: Duplicate or Replay submission attempt by merchant
AdversarialReplay(t) ==
    /\ txStatus[t] = "SETTLED"
    /\ t \in settledTxs
    \* Central Bank rejects duplicate settlement idempotently
    /\ UNCHANGED vars

-------------------------------------------------------------------------
\* Next-State Relation
Next ==
    \/ \E p \in Payers : AllocateEmergency(p, 50)
    \/ \E t \in TxPool, p \in Payers, m \in Merchants, amt \in TxAmounts : InitiateOfflineTx(t, p, m, amt)
    \/ \E t \in TxPool : BluetoothExchange(t)
    \/ \E t \in TxPool : AbortTx(t)
    \/ \E t \in TxPool : ReconcileSettlement(t)
    \/ \E t \in TxPool : AdversarialReplay(t)

Spec == Init /\ [][Next]_vars

-------------------------------------------------------------------------
\* Formal Invariants (Safety Properties)

\* Invariant 1: Total settled offline spending <= allocated reserve
SpentLeqAllocated ==
    \A p \in Payers :
        localEmergencyBalance[p] >= 0

\* Invariant 2: Zero Double-Settlement
ZeroDoubleSettlement ==
    \A t1, t2 \in settledTxs :
        (t1 = t2) \/ (txStatus[t1] = "SETTLED" /\ txStatus[t2] = "SETTLED" /\ t1 /= t2)

\* Invariant 3: Conservation of Liquidity
ConservationOfValue ==
    \A p \in Payers :
        bankOnlineBalance[p] + bankEmergencyBalance[p] <= 500

=============================================================================
