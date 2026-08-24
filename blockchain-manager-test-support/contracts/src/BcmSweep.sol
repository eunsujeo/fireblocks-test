// SPDX-License-Identifier: MIT
pragma solidity 0.8.35;

interface IERC20SweepToken {
    function balanceOf(address account) external view returns (uint256);

    function transferFrom(address owner, address recipient, uint256 amount) external returns (bool);
}

contract BcmSweep {
    struct SweepRequest {
        address owner;
        uint256 amount;
    }

    address public immutable operator;
    address public immutable destination;
    address public immutable allowedToken;
    uint32 public immutable maxItems;
    uint256 public immutable itemAmountCap;
    uint256 public immutable totalAmountCap;
    mapping(bytes16 executionId => bool used) public usedExecutions;

    bool private entered;

    bytes32 private constant TRANSFER_FROM_FAILED = keccak256("TRANSFER_FROM_FAILED");
    bytes32 private constant ACTUAL_AMOUNT_MISMATCH = keccak256("ACTUAL_AMOUNT_MISMATCH");

    event SweepLeg(
        bytes16 indexed executionId,
        uint32 indexed itemSeq,
        address indexed owner,
        uint256 requestedAmount,
        uint256 actualAmount,
        bool success,
        bytes32 failureCode
    );

    event SweepDone(bytes16 indexed executionId, uint32 itemCount, uint32 successCount, uint256 actualTotalAmount);

    constructor(
        address operatorAddress,
        address destinationAddress,
        address tokenAddress,
        uint32 maximumItems,
        uint256 maximumItemAmount,
        uint256 maximumTotalAmount
    ) {
        require(operatorAddress != address(0), "ZERO_OPERATOR");
        require(destinationAddress != address(0), "ZERO_DESTINATION");
        require(tokenAddress != address(0), "ZERO_TOKEN");
        require(maximumItems > 0, "ZERO_MAX_ITEMS");
        require(maximumItemAmount > 0, "ZERO_ITEM_CAP");
        require(maximumTotalAmount >= maximumItemAmount, "INVALID_TOTAL_CAP");
        operator = operatorAddress;
        destination = destinationAddress;
        allowedToken = tokenAddress;
        maxItems = maximumItems;
        itemAmountCap = maximumItemAmount;
        totalAmountCap = maximumTotalAmount;
    }

    function batchSweep(bytes16 executionId, address token, SweepRequest[] calldata items) external {
        require(msg.sender == operator, "ONLY_OPERATOR");
        require(!entered, "REENTRANCY");
        require(!usedExecutions[executionId], "EXECUTION_REUSED");
        require(token == allowedToken, "TOKEN_NOT_ALLOWED");
        require(items.length > 0 && items.length <= maxItems, "INVALID_ITEM_COUNT");

        uint256 requestedTotal;
        address previousOwner;
        uint32 itemCount;
        for (uint256 index = 0; index < items.length; index++) {
            SweepRequest calldata item = items[index];
            require(item.owner > previousOwner, "OWNERS_NOT_SORTED");
            require(item.amount > 0 && item.amount <= itemAmountCap, "ITEM_CAP_EXCEEDED");
            requestedTotal += item.amount;
            previousOwner = item.owner;
            itemCount++;
        }
        require(requestedTotal <= totalAmountCap, "TOTAL_CAP_EXCEEDED");

        entered = true;
        usedExecutions[executionId] = true;
        uint32 successCount;
        uint32 itemSequence = 1;
        uint256 actualTotalAmount;

        for (uint256 index = 0; index < items.length; index++) {
            SweepRequest calldata item = items[index];
            uint256 balanceBefore = IERC20SweepToken(token).balanceOf(destination);
            (bool called, bytes memory returned) = token.call(
                abi.encodeCall(IERC20SweepToken.transferFrom, (item.owner, destination, item.amount))
            );
            bool accepted = called && (returned.length == 0 || (returned.length == 32 && abi.decode(returned, (bool))));
            uint256 balanceAfter = IERC20SweepToken(token).balanceOf(destination);
            uint256 actualAmount = balanceAfter - balanceBefore;
            bool success = accepted && actualAmount == item.amount;
            bytes32 failureCode;
            if (success) {
                successCount++;
                actualTotalAmount += actualAmount;
            } else if (!accepted) {
                failureCode = TRANSFER_FROM_FAILED;
            } else {
                failureCode = ACTUAL_AMOUNT_MISMATCH;
            }
            emit SweepLeg(
                executionId,
                itemSequence,
                item.owner,
                item.amount,
                actualAmount,
                success,
                failureCode
            );
            if (index + 1 < items.length) {
                itemSequence++;
            }
        }

        entered = false;
        emit SweepDone(executionId, itemCount, successCount, actualTotalAmount);
    }
}
