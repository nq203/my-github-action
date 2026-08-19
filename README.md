# My GitHub Action

A robust GitHub Action for executing workflows with support for retry logic, error handling, and step-by-step execution.

## 📚 Documentation

The documentation has been cleaned up and organized:

### Quick Start
- **[00_START_HERE.md](00_START_HERE.md)** - Implementation plan and getting started guide
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)** - Quick reference and checklists while coding

### Reference
- **[ARCHITECTURE_DIAGRAM.md](ARCHITECTURE_DIAGRAM.md)** - System architecture and design diagrams

### Historical Documentation
All historical, archived, and old implementation plans have been moved to `/docs/archive/` to keep the project root clean.

## 🏗️ Architecture Overview

The project implements an execution engine with the following key components:

- **ExecutionEngine** - Main orchestrator for workflow execution
- **StepExecutor** - Handles individual step execution with retry logic
- **Error Handling** - Comprehensive error handling and recovery mechanisms
- **Database** - Persistent storage of execution state and history

## 🚀 Quick Start

1. Read [00_START_HERE.md](00_START_HERE.md) for implementation plan
2. Use [QUICK_REFERENCE.md](QUICK_REFERENCE.md) while coding
3. Refer to [ARCHITECTURE_DIAGRAM.md](ARCHITECTURE_DIAGRAM.md) for system design

## 📁 Project Structure

```
my-github-action/
├── src/              # Source code
├── docs/
│   ├── archive/      # Historical documentation
│   └── ...           # Project documentation
├── README.md         # This file
└── ...               # Configuration files
```

## 📝 License

See LICENSE file for details.

## ✨ Features

- Sequential workflow execution
- Automatic retry logic for transient failures
- Comprehensive error handling
- Persistent execution state
- Detailed execution logging

---

**Last Updated**: 2026-08-11  
**Documentation Status**: Cleaned and Reorganized
