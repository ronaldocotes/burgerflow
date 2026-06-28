/** @type {import('jest').Config} */
const config = {
  testEnvironment: 'jsdom',
  passWithNoTests: true,
  modulePathIgnorePatterns: ['<rootDir>/.next/'],
  testPathIgnorePatterns: ['<rootDir>/.next/', '<rootDir>/node_modules/', '<rootDir>/tests/e2e/'],
};

module.exports = config;
