package org.hitchain.exporer;

import org.apache.commons.lang3.tuple.Pair;
import org.ethereum.config.BlockchainConfig;
import org.ethereum.config.CommonConfig;
import org.ethereum.config.SystemProperties;
import org.ethereum.core.AccountState;
import org.ethereum.core.Block;
import org.ethereum.core.Repository;
import org.ethereum.core.Transaction;
import org.ethereum.core.TransactionExecutionSummary;
import org.ethereum.core.TransactionReceipt;
import org.ethereum.db.BlockStore;
import org.ethereum.db.ContractDetails;
import org.ethereum.listener.EthereumListener;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.util.ByteArraySet;
import org.ethereum.vm.*;
import org.ethereum.vm.hook.VMHook;
import org.ethereum.vm.program.Program;
import org.ethereum.vm.program.ProgramResult;
import org.ethereum.vm.program.invoke.ProgramInvoke;
import org.ethereum.vm.program.invoke.ProgramInvokeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;

import static java.util.Objects.isNull;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.ArrayUtils.isEmpty;
import static org.ethereum.util.BIUtil.*;
import static org.ethereum.util.ByteUtil.EMPTY_BYTE_ARRAY;
import static org.ethereum.util.ByteUtil.toHexString;
import static org.ethereum.vm.VMUtils.saveProgramTraceFile;
import static org.ethereum.vm.VMUtils.zipAndEncode;


public class TransactionExecutor {

    private static final Logger logger = LoggerFactory.getLogger("execute");
    private static final Logger stateLogger = LoggerFactory.getLogger("state");

    SystemProperties config;
    CommonConfig commonConfig;
    BlockchainConfig blockchainConfig;

    private org.ethereum.core.Transaction tx;
    private org.ethereum.core.Repository track;
    private org.ethereum.core.Repository cacheTrack;
    private BlockStore blockStore;
    private final long gasUsedInTheBlock;
    private boolean readyToExecute = false;
    private String execError;

    private ProgramInvokeFactory programInvokeFactory;
    private byte[] coinbase;

    private TransactionReceipt receipt;
    private ProgramResult result = new ProgramResult();
    private org.ethereum.core.Block currentBlock;

    private final EthereumListener listener;

    private VM vm;
    private Program program;

    PrecompiledContracts.PrecompiledContract precompiledContract;

    BigInteger m_endGas = BigInteger.ZERO;
    long basicTxCost = 0;
    List<LogInfo> logs = null;

    private ByteArraySet touchedAccounts = new ByteArraySet();

    boolean localCall = false;
    private final VMHook vmHook;

    public TransactionExecutor(org.ethereum.core.Transaction tx, byte[] coinbase, org.ethereum.core.Repository track, BlockStore blockStore,
                               ProgramInvokeFactory programInvokeFactory, org.ethereum.core.Block currentBlock) {

        this(tx, coinbase, track, blockStore, programInvokeFactory, currentBlock, new EthereumListenerAdapter(), 0, VMHook.EMPTY);
    }

    public TransactionExecutor(org.ethereum.core.Transaction tx, byte[] coinbase, org.ethereum.core.Repository track, BlockStore blockStore,
                               ProgramInvokeFactory programInvokeFactory, org.ethereum.core.Block currentBlock,
                               EthereumListener listener, long gasUsedInTheBlock) {
        this(tx, coinbase,track, blockStore, programInvokeFactory, currentBlock, listener, gasUsedInTheBlock, VMHook.EMPTY);
    }

    public TransactionExecutor(Transaction tx, byte[] coinbase, org.ethereum.core.Repository track, BlockStore blockStore,
                               ProgramInvokeFactory programInvokeFactory, Block currentBlock,
                               EthereumListener listener, long gasUsedInTheBlock, VMHook vmHook) {

        this.tx = tx;
        this.coinbase = coinbase;
        this.track = track;
        this.cacheTrack = track.startTracking();
        this.blockStore = blockStore;
        this.programInvokeFactory = programInvokeFactory;
        this.currentBlock = currentBlock;
        this.listener = listener;
        this.gasUsedInTheBlock = gasUsedInTheBlock;
        this.m_endGas = toBI(tx.getGasLimit());
        this.vmHook = isNull(vmHook) ? VMHook.EMPTY : vmHook;

        withCommonConfig(CommonConfig.getDefault());
    }

    public TransactionExecutor withCommonConfig(CommonConfig commonConfig) {
        this.commonConfig = commonConfig;
        this.config = commonConfig.systemProperties();
        this.blockchainConfig = config.getBlockchainConfig().getConfigForBlock(currentBlock.getNumber());
        return this;
    }

    private void execError(String err) {
        logger.warn(err);
        execError = err;
    }

    /**
     * Do all the basic validation, if the executor
     * will be ready to run the transaction at the end
     * set readyToExecute = true
     */
    public void init() {
        basicTxCost = tx.transactionCost(config.getBlockchainConfig(), currentBlock);

        if (localCall) {
            readyToExecute = true;
            return;
        }

        BigInteger txGasLimit = new BigInteger(1, tx.getGasLimit());
        BigInteger curBlockGasLimit = new BigInteger(1, currentBlock.getGasLimit());

        boolean cumulativeGasReached = txGasLimit.add(BigInteger.valueOf(gasUsedInTheBlock)).compareTo(curBlockGasLimit) > 0;
        if (cumulativeGasReached) {

            execError(String.format("Too much gas used in this block: Require: %s Got: %s", new BigInteger(1, currentBlock.getGasLimit()).longValue() - toBI(tx.getGasLimit()).longValue(), toBI(tx.getGasLimit()).longValue()));

            return;
        }

        if (txGasLimit.compareTo(BigInteger.valueOf(basicTxCost)) < 0) {

            execError(String.format("Not enough gas for transaction execution: Require: %s Got: %s", basicTxCost, txGasLimit));

            return;
        }

        BigInteger reqNonce = track.getNonce(tx.getSender());
        BigInteger txNonce = toBI(tx.getNonce());
        if (isNotEqual(reqNonce, txNonce)) {
            execError(String.format("Invalid nonce: required: %s , tx.nonce: %s", reqNonce, txNonce));

            return;
        }

        BigInteger txGasCost = toBI(tx.getGasPrice()).multiply(txGasLimit);
        BigInteger totalCost = toBI(tx.getValue()).add(txGasCost);
        BigInteger senderBalance = track.getBalance(tx.getSender());

        if (!isCovers(senderBalance, totalCost)) {

            execError(String.format("Not enough cash: Require: %s, Sender cash: %s", totalCost, senderBalance));

            return;
        }

        if (!blockchainConfig.acceptTransactionSignature(tx)) {
            execError("Transaction signature not accepted: " + tx.getSignature());
            return;
        }

        readyToExecute = true;
    }

    public void execute() {

        if (!readyToExecute) return;

        if (!localCall) {
            track.increaseNonce(tx.getSender());

            BigInteger txGasLimit = toBI(tx.getGasLimit());
            BigInteger txGasCost = toBI(tx.getGasPrice()).multiply(txGasLimit);
            track.addBalance(tx.getSender(), txGasCost.negate());

            if (logger.isInfoEnabled())
                logger.info("Paying: txGasCost: [{}], gasPrice: [{}], gasLimit: [{}]", txGasCost, toBI(tx.getGasPrice()), txGasLimit);
        }

        if (tx.isContractCreation()) {
            create();
        } else {
            call();
        }
    }

    private void call() {
        if (!readyToExecute) return;

        byte[] targetAddress = tx.getReceiveAddress();
        precompiledContract = PrecompiledContracts.getContractForAddress(DataWord.of(targetAddress), blockchainConfig);

        if (precompiledContract != null) {
            long requiredGas = precompiledContract.getGasForData(tx.getData());

            BigInteger spendingGas = BigInteger.valueOf(requiredGas).add(BigInteger.valueOf(basicTxCost));

            if (!localCall && m_endGas.compareTo(spendingGas) < 0) {
                // no refund
                // no endowment
                execError("Out of Gas calling precompiled contract 0x" + toHexString(targetAddress) +
                        ", required: " + spendingGas + ", left: " + m_endGas);
                m_endGas = BigInteger.ZERO;
                return;
            } else {

                m_endGas = m_endGas.subtract(spendingGas);

                // FIXME: save return for vm trace
                Pair<Boolean, byte[]> out = precompiledContract.execute(tx.getData());

                if (!out.getLeft()) {
                    execError("Error executing precompiled contract 0x" + toHexString(targetAddress));
                    m_endGas = BigInteger.ZERO;
                    return;
                }
            }

        } else {

            byte[] code = track.getCode(targetAddress);
            if (isEmpty(code)) {
                m_endGas = m_endGas.subtract(BigInteger.valueOf(basicTxCost));
                result.spendGas(basicTxCost);
            } else {
                ProgramInvoke programInvoke =
                        programInvokeFactory.createProgramInvoke(tx, currentBlock, cacheTrack, track, blockStore);

                this.vm = new VM(config, vmHook);
                this.program = new Program(track.getCodeHash(targetAddress), code, programInvoke, tx, config, vmHook).withCommonConfig(commonConfig);
            }
        }

        BigInteger endowment = toBI(tx.getValue());
        transfer(cacheTrack, tx.getSender(), targetAddress, endowment);

        touchedAccounts.add(targetAddress);
    }

    private void create() {
        byte[] newContractAddress = tx.getContractAddress();

        org.ethereum.core.AccountState existingAddr = cacheTrack.getAccountState(newContractAddress);
        if (existingAddr != null && existingAddr.isContractExist(blockchainConfig)) {
            execError("Trying to create a contract with existing contract address: 0x" + toHexString(newContractAddress));
            m_endGas = BigInteger.ZERO;
            return;
        }

        //In case of hashing collisions (for TCK tests only), check for any balance before createAccount()
        BigInteger oldBalance = track.getBalance(newContractAddress);
        cacheTrack.createAccount(tx.getContractAddress());
        cacheTrack.addBalance(newContractAddress, oldBalance);
        if (blockchainConfig.eip161()) {
            cacheTrack.increaseNonce(newContractAddress);
        }

        if (isEmpty(tx.getData())) {
            m_endGas = m_endGas.subtract(BigInteger.valueOf(basicTxCost));
            result.spendGas(basicTxCost);
        } else {
            Repository originalRepo = track;
            // Some TCK tests have storage only addresses (no code, zero nonce etc) - impossible situation in the real network
            // So, we should clean up it before reuse, but as tx not always goes successful, state should be correctly
            // reverted in that case too
            if (cacheTrack.hasContractDetails(newContractAddress)) {
                originalRepo = track.clone();
                originalRepo.delete(newContractAddress);
            }
            ProgramInvoke programInvoke = programInvokeFactory.createProgramInvoke(tx, currentBlock,
                    cacheTrack, originalRepo, blockStore);

            this.vm = new VM(config, vmHook);
            this.program = new Program(tx.getData(), programInvoke, tx, config, vmHook).withCommonConfig(commonConfig);

            // reset storage if the contract with the same address already exists
            // TCK test case only - normally this is near-impossible situation in the real network
            ContractDetails contractDetails = program.getStorage().getContractDetails(newContractAddress);
            contractDetails.deleteStorage();
        }

        BigInteger endowment = toBI(tx.getValue());
        transfer(cacheTrack, tx.getSender(), newContractAddress, endowment);

        touchedAccounts.add(newContractAddress);
    }

    public void go() {
        if (!readyToExecute) return;

        try {

            if (vm != null) {

                // Charge basic cost of the transaction
                program.spendGas(tx.transactionCost(config.getBlockchainConfig(), currentBlock), "TRANSACTION COST");

                if (config.playVM())
                    vm.play(program);

                result = program.getResult();
                m_endGas = toBI(tx.getGasLimit()).subtract(toBI(program.getResult().getGasUsed()));

                if (tx.isContractCreation() && !result.isRevert()) {
                    int returnDataGasValue = getLength(program.getResult().getHReturn()) *
                            blockchainConfig.getGasCost().getCREATE_DATA();
                    if (m_endGas.compareTo(BigInteger.valueOf(returnDataGasValue)) < 0) {
                        // Not enough gas to return contract code
                        if (!blockchainConfig.getConstants().createEmptyContractOnOOG()) {
                            program.setRuntimeFailure(Program.Exception.notEnoughSpendingGas("No gas to return just created contract",
                                    returnDataGasValue, program));
                            result = program.getResult();
                        }
                        result.setHReturn(EMPTY_BYTE_ARRAY);
                    } else if (getLength(result.getHReturn()) > blockchainConfig.getConstants().getMAX_CONTRACT_SZIE()) {
                        // Contract size too large
                        program.setRuntimeFailure(Program.Exception.notEnoughSpendingGas("Contract size too large: " + getLength(result.getHReturn()),
                                returnDataGasValue, program));
                        result = program.getResult();
                        result.setHReturn(EMPTY_BYTE_ARRAY);
                    } else {
                        // Contract successfully created
                        m_endGas = m_endGas.subtract(BigInteger.valueOf(returnDataGasValue));
                        cacheTrack.saveCode(tx.getContractAddress(), result.getHReturn());
                    }
                }

                String err = config.getBlockchainConfig().getConfigForBlock(currentBlock.getNumber()).
                        validateTransactionChanges(blockStore, currentBlock, tx, null);
                if (err != null) {
                    program.setRuntimeFailure(new RuntimeException("Transaction changes validation failed: " + err));
                }


                if (result.getException() != null || result.isRevert()) {
                    result.getDeleteAccounts().clear();
                    result.getLogInfoList().clear();
                    result.resetFutureRefund();
                    rollback();

                    if (result.getException() != null) {
                        throw result.getException();
                    } else {
                        execError("REVERT opcode executed");
                    }
                } else {
                    touchedAccounts.addAll(result.getTouchedAccounts());
                    cacheTrack.commit();
                }

            } else {
                cacheTrack.commit();
            }

        } catch (Throwable e) {

            // TODO: catch whatever they will throw on you !!!
//            https://github.com/ethereum/cpp-ethereum/blob/develop/libethereum/Executive.cpp#L241
            rollback();
            m_endGas = BigInteger.ZERO;
            execError(e.getMessage());
        }
    }

    private void rollback() {

        cacheTrack.rollback();

        // remove touched account
        touchedAccounts.remove(
                tx.isContractCreation() ? tx.getContractAddress() : tx.getReceiveAddress());
    }

    public org.ethereum.core.TransactionExecutionSummary finalization() {
        if (!readyToExecute) return null;

        org.ethereum.core.TransactionExecutionSummary.Builder summaryBuilder = org.ethereum.core.TransactionExecutionSummary.builderFor(tx)
                .gasLeftover(m_endGas)
                .logs(result.getLogInfoList())
                .result(result.getHReturn());

        if (result != null) {
            // Accumulate refunds for suicides
            result.addFutureRefund(result.getDeleteAccounts().size() * config.getBlockchainConfig().
                    getConfigForBlock(currentBlock.getNumber()).getGasCost().getSUICIDE_REFUND());
            long gasRefund = Math.min(Math.max(0, result.getFutureRefund()), getGasUsed() / 2);
            byte[] addr = tx.isContractCreation() ? tx.getContractAddress() : tx.getReceiveAddress();
            m_endGas = m_endGas.add(BigInteger.valueOf(gasRefund));

            summaryBuilder
                    .gasUsed(toBI(result.getGasUsed()))
                    .gasRefund(toBI(gasRefund))
                    .deletedAccounts(result.getDeleteAccounts())
                    .internalTransactions(result.getInternalTransactions());

            ContractDetails contractDetails = track.getContractDetails(addr);
            if (contractDetails != null) {
                // TODO
//                summaryBuilder.storageDiff(track.getContractDetails(addr).getStorage());
//
//                if (program != null) {
//                    summaryBuilder.touchedStorage(contractDetails.getStorage(), program.getStorageDiff());
//                }
            }

            if (result.getException() != null) {
                summaryBuilder.markAsFailed();
            }
        }

        TransactionExecutionSummary summary = summaryBuilder.build();

        // Refund for gas leftover
        track.addBalance(tx.getSender(), summary.getLeftover().add(summary.getRefund()));
        logger.info("Pay total refund to sender: [{}], refund val: [{}]", toHexString(tx.getSender()), summary.getRefund());

        // Transfer fees to miner
        track.addBalance(coinbase, summary.getFee());
        touchedAccounts.add(coinbase);
        logger.info("Pay fees to miner: [{}], feesEarned: [{}]", toHexString(coinbase), summary.getFee());

        if (result != null) {
            logs = result.getLogInfoList();
            // Traverse list of suicides
            for (DataWord address : result.getDeleteAccounts()) {
                track.delete(address.getLast20Bytes());
            }
        }

        if (blockchainConfig.eip161()) {
            for (byte[] acctAddr : touchedAccounts) {
                AccountState state = track.getAccountState(acctAddr);
                if (state != null && state.isEmpty()) {
                    track.delete(acctAddr);
                }
            }
        }


        listener.onTransactionExecuted(summary);

        if (config.vmTrace() && program != null && result != null) {
            String trace = program.getTrace()
                    .result(result.getHReturn())
                    .error(result.getException())
                    .toString();


            if (config.vmTraceCompressed()) {
                trace = zipAndEncode(trace);
            }

            String txHash = toHexString(tx.getHash());
            saveProgramTraceFile(config, txHash, trace);
            listener.onVMTraceCreated(txHash, trace);
        }
        return summary;
    }

    public TransactionExecutor setLocalCall(boolean localCall) {
        this.localCall = localCall;
        return this;
    }


    public TransactionReceipt getReceipt() {
        if (receipt == null) {
            receipt = new TransactionReceipt();
            long totalGasUsed = gasUsedInTheBlock + getGasUsed();
            receipt.setCumulativeGas(totalGasUsed);
            receipt.setTransaction(tx);
            receipt.setLogInfoList(getVMLogs());
            receipt.setGasUsed(getGasUsed());
            receipt.setExecutionResult(getResult().getHReturn());
            receipt.setError(execError);
//            receipt.setPostTxState(track.getRoot()); // TODO later when RepositoryTrack.getRoot() is implemented
        }
        return receipt;
    }

    public List<LogInfo> getVMLogs() {
        return logs;
    }

    public ProgramResult getResult() {
        return result;
    }

    public long getGasUsed() {
        return toBI(tx.getGasLimit()).subtract(m_endGas).longValue();
    }

}
