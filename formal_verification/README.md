# EmPay-Lab: Formal Verification with TLA+ and TLC

This directory contains the formal mathematical specification and model checker configuration for the **EmPay-Lab** offline payment protocol.

## Protocol Specification Files
- **`EmPayProtocol.tla`**: Formal specification written in TLA+ (Temporal Logic of Actions). Models the complete transaction lifecycle, allocation, Bluetooth exchange, and bank reconciliation.
- **`EmPayProtocol.cfg`**: TLC model configuration file specifying the model bounds, constant sets, and safety invariants.

## Verified Invariants

1. **Safety Invariant 1: Spent $\le$ Allocated (`SpentLeqAllocated`)**
   $$\forall p \in \text{Payers} : \text{localEmergencyBalance}[p] \ge 0$$
   Guarantees that an offline payer can never spend beyond the funds actively committed to their local hardware reserve.

2. **Safety Invariant 2: Zero Double-Settlement (`ZeroDoubleSettlement`)**
   $$\forall t_1, t_2 \in \text{settledTxs} : t_1 \ne t_2 \implies \text{txId}(t_1) \ne \text{txId}(t_2)$$
   Proves that no transaction can be reconciled or settled twice by the Central Bank ledger, preventing duplicate claims or merchant re-submissions.

3. **Safety Invariant 3: Conservation of System Liquidity (`ConservationOfValue`)**
   $$\forall p \in \text{Payers} : \text{bankOnlineBalance}[p] + \text{bankEmergencyBalance}[p] \le \text{InitialBalance}$$
   Guarantees that offline allocation and subsequent reconciliation can never manufacture "phantom money" or inflate ledger balances.

## How to Run TLC Model Checker

### Option 1: Using TLA+ Toolbox (GUI)
1. Open the [TLA+ Toolbox](https://lamport.azurewebsites.net/tla/toolbox.html).
2. Click **File -> Open Spec -> Add New Spec...** and choose `EmPayProtocol.tla`.
3. In the Model Editor, click **TLC Model Checker -> Run model**.
4. TLC will traverse all reachable states and confirm:
   `Model checking completed. No errors found.`

### Option 2: Command Line (Java TLC runner)
```bash
java -jar tla2tools.jar -config EmPayProtocol.cfg EmPayProtocol.tla
```
