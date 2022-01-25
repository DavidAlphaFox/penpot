/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

 "use strict";

 import {

  createProject,
  deleteFirstProject

} from '../../support/utils.js';



 describe("projects", () => {
   beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password)
    });
     
   });
 
   it("displays the projects page", () => {
     cy.get(".dashboard-title").should("contain", "Projects");    
   });

   it("can create a new project", () => {
    let projectName = "test project " + Date.now();
    cy.get(".project").then((projects) => {
      cy.get("[data-e2e=new-project-button]").click();
      cy.get('.project').should('have.length', projects.length + 1);
      cy.get('.project').first().find(".edit-wrapper").type(projectName + "{enter}")
      cy.get('.project').first().find("h2").should("contain", projectName);

      //cleanup: delete project
      deleteFirstProject();
    })
      
  })

  it("can rename a project", () => {     
      let projectName = "test project " + Date.now(); 
      let projectName2 = "renamed project " + Date.now(); 
      cy.get("[data-e2e=new-project-button]").click();
      cy.get('.project').first().find(".edit-wrapper").type(projectName + "{enter}");
      cy.get('.project').first().find("h2").should("contain", projectName);

      cy.get('.project').first().find("[data-e2e=project-options]").click();
      cy.get('.project').first().find("[data-e2e=Rename]").click();
      cy.get('.project').first().find(".edit-wrapper").type(projectName2 + "{enter}")
      cy.get('.project').first().find("h2").should("contain", projectName2)

      //cleanup: delete project
      deleteFirstProject();   
  });

  it.only("can delete a project", () => {     
    cy.get("[data-e2e=new-project-button]").click();
    cy.get('.project').first().find(".edit-wrapper > .element-title").type("{enter}");
    cy.get(".project").then((projects) => {
      cy.get('.project').first().find("[data-e2e=project-options]").click();
      cy.get('.project').first().find("[data-e2e=Delete]").click();
      cy.get('.accept-button').click();
      cy.get('.project').should('have.length', projects.length - 1);
    })
  });

  it("can cancel the deletion of a project", () => {     
    cy.get("[data-e2e=new-project-button]").click();
    cy.get('.project').first().find(".edit-wrapper").type("{enter}");
    cy.get(".project").then((projects) => {
      cy.get('.project').first().find("[data-e2e=project-options]").click();
      cy.get('.project').first().find("[data-e2e=Delete]").click();
      cy.get('.accept-button').click();
      cy.get('.project').should('have.length', projects.length - 1);
    })
  });
/*
  it("can cancel the deletion of a project", () => {     
    cy.get("[data-e2e=new-project-button]").click();
    cy.get('.project').first().find(".edit-wrapper").type("{enter}");
    cy.get(".project").then((projects) => {
      cy.get('.project').first().find("[data-e2e=project-options]").click();
      cy.get('.project').first().find("[data-e2e=Delete]").click();
      cy.get('.cancel-button').click();
      cy.get('.project').should('have.length', projects.length);

      //cleanup: delete project
      deleteFirstProject(); 
    })
  });
*/
 });
 
 