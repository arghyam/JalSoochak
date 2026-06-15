# README

## Overview

**JalSoochak** ("water informer") is a **Digital Public Good (DPG)** for monitoring rural drinking-water supply, built around WhatsApp-driven field data collection and public analytics dashboards under India's **Jal Jeevan Mission (JJM)**.

It is a **multi-tenant** platform for state-level water-supply monitoring, featuring:

* A **microservices** backend (Java 21 + Spring Boot)
* **WhatsApp integration via Glific** for field operators, with AI-assisted meter-reading extraction
* **Schema-per-tenant** data isolation on PostgreSQL
* **Cloud-neutral** deployment on any Kubernetes cluster

## Structure

* **Introduction** — purpose, scope, and DPG orientation
* **Users & Tenancy** — multi-tenant model, roles, and provisioning
* **Functional Overview** — core functional modules
* **Technical Architecture** — microservices, communication, and security
* **Deployment Architecture** — infrastructure and environments
* **Technology Stack** — languages, frameworks, and infrastructure
* **Architecture Decision Records** — key design decisions and rationale
* **API Specifications** — per-service REST API reference
* **Database Design** — multi-tenant schema and analytics warehouse
* **Non-functional Requirements** — security, scalability, performance, observability
* **Future Work** — roadmap and known gaps

For implementation details, build instructions, and source code, see the main project repository.
