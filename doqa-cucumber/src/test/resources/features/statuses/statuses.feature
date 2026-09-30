@statuses
Feature: Outcomes

  Scenario: Assertion fails
    Given a passing step
    When an assertion fails
    Then a passing step

  Scenario: Code breaks
    Given a runtime exception is thrown

  Scenario: Pending step
    Given a pending step

  Scenario: Undefined step
    Given a step nobody defined
    Then a passing step

  Scenario: Ambiguous step
    Given an ambiguous step

  Scenario: Assumption skips
    Given the test is aborted

  @fail_before
  Scenario: Before hook fails
    Given a passing step

  @fail_after
  Scenario: After hook fails
    Given a passing step

  @fail_before_step
  Scenario: BeforeStep hook fails
    Given a passing step

  @fail_after_step
  Scenario: AfterStep hook fails
    Given a passing step
