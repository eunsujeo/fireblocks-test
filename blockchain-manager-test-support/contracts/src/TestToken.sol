// SPDX-License-Identifier: MIT
pragma solidity 0.8.35;

contract TestToken {
    string public constant name = "BCM Test USD";
    string public constant symbol = "TUSD";
    uint8 public constant decimals = 6;

    address public immutable minter;
    mapping(address account => uint256 amount) public balanceOf;
    mapping(address owner => mapping(address spender => uint256 amount)) public allowance;

    event Transfer(address indexed from, address indexed to, uint256 amount);
    event Approval(address indexed owner, address indexed spender, uint256 amount);

    constructor() {
        minter = msg.sender;
    }

    function mint(address recipient, uint256 amount) external {
        require(msg.sender == minter, "ONLY_MINTER");
        require(recipient != address(0), "ZERO_RECIPIENT");
        balanceOf[recipient] += amount;
        emit Transfer(address(0), recipient, amount);
    }

    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    function transfer(address recipient, uint256 amount) external returns (bool) {
        require(balanceOf[msg.sender] >= amount, "INSUFFICIENT_BALANCE");
        balanceOf[msg.sender] -= amount;
        balanceOf[recipient] += amount;
        emit Transfer(msg.sender, recipient, amount);
        return true;
    }

    function transferFrom(address owner, address recipient, uint256 amount) external returns (bool) {
        uint256 approved = allowance[owner][msg.sender];
        require(approved >= amount, "INSUFFICIENT_ALLOWANCE");
        require(balanceOf[owner] >= amount, "INSUFFICIENT_BALANCE");
        allowance[owner][msg.sender] = approved - amount;
        balanceOf[owner] -= amount;
        balanceOf[recipient] += amount;
        emit Transfer(owner, recipient, amount);
        return true;
    }
}
