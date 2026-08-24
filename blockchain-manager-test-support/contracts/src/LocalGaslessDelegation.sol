// SPDX-License-Identifier: MIT
pragma solidity 0.8.35;

contract LocalGaslessDelegation {
    bytes32 private constant INTENT_TAG = keccak256("BCM_LOCAL_GASLESS_INTENT_V1");

    address public immutable feePayer;
    uint256 public executionNonce;

    constructor(address feePayerAddress) {
        require(feePayerAddress != address(0), "ZERO_FEE_PAYER");
        feePayer = feePayerAddress;
    }

    function execute(
        address target,
        uint256 value,
        bytes calldata data,
        uint256 nonce,
        uint256 deadline,
        bytes calldata signature
    ) external returns (bytes memory result) {
        require(msg.sender == feePayer, "ONLY_FEE_PAYER");
        // forge-lint: disable-next-line(block-timestamp)
        require(block.timestamp <= deadline, "INTENT_EXPIRED");
        require(nonce == executionNonce, "INTENT_NONCE");
        require(signature.length == 65, "INVALID_SIGNATURE_LENGTH");

        bytes32 digest = keccak256(
            abi.encode(INTENT_TAG, block.chainid, address(this), target, value, keccak256(data), nonce, deadline)
        );
        bytes32 r;
        bytes32 s;
        uint8 v;
        assembly ("memory-safe") {
            r := calldataload(signature.offset)
            s := calldataload(add(signature.offset, 32))
            v := byte(0, calldataload(add(signature.offset, 64)))
        }
        require(ecrecover(digest, v, r, s) == address(this), "INVALID_INTENT_SIGNER");

        executionNonce = nonce + 1;
        bool successful;
        (successful, result) = target.call{value: value}(data);
        if (!successful) {
            assembly ("memory-safe") {
                revert(add(result, 32), mload(result))
            }
        }
    }
}
